/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.factwindow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class ValkeyFactWindowStoreIntegrationTest {
  @Test
  void appendsTrimsAndRefreshesTtlAgainstRealValkey() {
    try (GenericContainer<?> valkey =
        new GenericContainer<>("valkey/valkey:8.1")
            .withExposedPorts(6379)
            .withCommand("--requirepass", "password")
            .waitingFor(Wait.forListeningPort())) {
      valkey.start();
      try (ValkeyFactWindowStore store =
          new ValkeyFactWindowStore(
              valkey.getHost(), valkey.getMappedPort(6379), "password", new ObjectMapper())) {
        assertEquals(
            1, store.appendAndLoad("payments", 1, "session", Map.of("n", 1), 2, 30).size());
        assertEquals(
            2, store.appendAndLoad("payments", 1, "session", Map.of("n", 2), 2, 30).size());
        var window = store.appendAndLoad("payments", 1, "session", Map.of("n", 3), 2, 30);
        assertEquals(2, window.size());
        assertEquals(2, window.get(0).get("n"));
        assertEquals(
            Duration.ofSeconds(30).toSeconds(), store.ttlSeconds("payments", 1, "session"));
      }
    }
  }
}
