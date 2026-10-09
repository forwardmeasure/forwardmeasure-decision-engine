/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.factwindow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.testcontainers.valkey.ValkeyTestContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ValkeyFactWindowStoreIntegrationTest {
  @Test
  void appendsTrimsAndRefreshesTtlAgainstRealValkey() {
    try (var valkey = new ValkeyTestContainer("window-test-password").start();
        var store = store(valkey)) {
      assertTrue(store.isHealthy());
      assertEquals(List.of(Map.of("n", 1)), store.appendAndLoad("payments", 1, "session", Map.of("n", 1), 2, 5));
      assertEquals(List.of(Map.of("n", 1), Map.of("n", 2)), store.appendAndLoad("payments", 1, "session", Map.of("n", 2), 2, 30));
      assertEquals(List.of(Map.of("n", 2), Map.of("n", 3)), store.appendAndLoad("payments", 1, "session", Map.of("n", 3), 2, 30));
      long ttl = store.ttlSeconds("payments", 1, "session");
      assertTrue(ttl > 5 && ttl <= 30, "An append must refresh the earlier five-second expiry");
    }
  }

  @Test
  void separatesRulesetsVersionsSessionsAndCallerSuppliedDelimiters() {
    try (var valkey = new ValkeyTestContainer("window-test-password").start();
        var store = store(valkey)) {
      store.appendAndLoad("a:1", 2, "b", Map.of("scope", "first"), 8, 30);
      // These components collided when joined with ':' without independent encoding.
      assertEquals(List.of(Map.of("scope", "second")), store.appendAndLoad("a", 1, "2:b", Map.of("scope", "second"), 8, 30));
      assertEquals(List.of(Map.of("scope", "version")), store.appendAndLoad("a:1", 3, "b", Map.of("scope", "version"), 8, 30));
      assertEquals(List.of(Map.of("scope", "session")), store.appendAndLoad("a:1", 2, "b/別", Map.of("scope", "session"), 8, 30));
      assertEquals(List.of(Map.of("scope", "first"), Map.of("scope", "again")), store.appendAndLoad("a:1", 2, "b", Map.of("scope", "again"), 8, 30));
    }
  }

  @Test
  void recoversAfterServerScriptCacheLossWithoutDuplicatingFacts() {
    try (var valkey = new ValkeyTestContainer("window-test-password").start();
        var store = store(valkey)) {
      store.appendAndLoad("payments", 1, "session", Map.of("n", 1), 8, 30);
      var client = RedisClient.create(RedisURI.Builder.redis(valkey.host(), valkey.port()).withPassword(valkey.password().toCharArray()).build());
      try (var connection = client.connect()) {
        connection.sync().scriptFlush();
      } finally {
        client.shutdown();
      }
      assertEquals(List.of(Map.of("n", 1), Map.of("n", 2)), store.appendAndLoad("payments", 1, "session", Map.of("n", 2), 8, 30));
      assertEquals(List.of(Map.of("n", 1), Map.of("n", 2), Map.of("n", 3)), store.appendAndLoad("payments", 1, "session", Map.of("n", 3), 8, 30));
    }
  }

  @Test
  void concurrentAppendsReturnTheirOwnAtomicBoundedWindow() throws Exception {
    try (var valkey = new ValkeyTestContainer("window-test-password").start();
        var store = store(valkey);
        var threads = Executors.newFixedThreadPool(4)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 24; i++) {
        int identity = i;
        tasks.add(threads.submit(() -> {
          var fact = Map.<String, Object>of("n", identity);
          var window = store.appendAndLoad("payments", 1, "shared", fact, 8, 30);
          assertTrue(window.size() <= 8);
          assertEquals(fact, window.getLast(), "Another writer must not interleave between append and read");
          assertEquals(window.size(), window.stream().distinct().count());
        }));
      }
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
      assertEquals(8, store.appendAndLoad("payments", 1, "shared", Map.of("n", 24), 8, 30).size());
    }
  }

  @Test
  void reconnectRetainsFactsButIdleExpiryStartsAFreshWindow() throws Exception {
    try (var valkey = new ValkeyTestContainer("window-test-password").start()) {
      try (var first = store(valkey)) {
        first.appendAndLoad("payments", 1, "session", Map.of("n", 1), 8, 30);
      }
      try (var reopened = store(valkey)) {
        assertEquals(List.of(Map.of("n", 1), Map.of("n", 2)), reopened.appendAndLoad("payments", 1, "session", Map.of("n", 2), 8, 1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (reopened.ttlSeconds("payments", 1, "session") != -2 && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(-2, reopened.ttlSeconds("payments", 1, "session"), "Idle window must expire");
        assertEquals(List.of(Map.of("n", 3)), reopened.appendAndLoad("payments", 1, "session", Map.of("n", 3), 8, 30));
      }
    }
  }

  @Test
  void invalidBoundsDoNotAppendPartialFacts() {
    try (var valkey = new ValkeyTestContainer("window-test-password").start();
        var store = store(valkey)) {
      for (int[] bounds : new int[][] {{0, 30}, {-1, 30}, {8, 0}, {8, -1}}) {
        assertThrows(IllegalArgumentException.class, () -> store.appendAndLoad("payments", 1, "session", Map.of("rejected", true), bounds[0], bounds[1]));
      }
      assertEquals(List.of(Map.of("accepted", true)), store.appendAndLoad("payments", 1, "session", Map.of("accepted", true), 8, 30));
    }
  }

  private static ValkeyFactWindowStore store(ValkeyTestContainer valkey) {
    return new ValkeyFactWindowStore(valkey.host(), valkey.port(), valkey.password(), new ObjectMapper());
  }
}
