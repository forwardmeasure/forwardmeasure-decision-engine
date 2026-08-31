/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.factwindow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.decisionengine.domain.FactWindowStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Valkey-backed bounded fact history with atomic append-and-load semantics. */
public final class ValkeyFactWindowStore implements FactWindowStore, AutoCloseable {

  private static final String APPEND_AND_LOAD_SCRIPT =
      """
      redis.call('RPUSH', KEYS[1], ARGV[1])
      redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1)
      redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
      return redis.call('LRANGE', KEYS[1], 0, -1)
      """;

  private static final TypeReference<Map<String, Object>> FACT_TYPE = new TypeReference<>() {};

  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final ObjectMapper objectMapper;
  private volatile String scriptSha;

  public ValkeyFactWindowStore(String host, int port, String password, ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    RedisURI.Builder builder = RedisURI.builder().withHost(host).withPort(port);
    if (password != null && !password.isBlank()) {
      builder.withPassword(password.toCharArray());
    }
    this.client = RedisClient.create(builder.build());
    this.connection = client.connect();
  }

  public ValkeyFactWindowStore(String host, int port, ObjectMapper objectMapper) {
    this(host, port, null, objectMapper);
  }

  @Override
  public List<Map<String, Object>> appendAndLoad(
      String ruleset,
      long rulesetVersion,
      String sessionKey,
      Map<String, Object> fact,
      int maxWindowSize,
      int idleTimeoutSeconds) {
    if (maxWindowSize <= 0 || idleTimeoutSeconds <= 0) {
      throw new IllegalArgumentException("fact-window bounds must be positive");
    }
    try {
      String encodedFact = objectMapper.writeValueAsString(fact);
      List<String> serialized =
          executeScript(
              key(ruleset, rulesetVersion, sessionKey),
              encodedFact,
              Integer.toString(maxWindowSize),
              Integer.toString(idleTimeoutSeconds));
      return serialized.stream().map(this::readFact).toList();
    } catch (Exception exception) {
      if (exception instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("unable to append fact to Valkey", exception);
    }
  }

  private List<String> executeScript(String key, String fact, String maxSize, String timeout) {
    RedisCommands<String, String> commands = connection.sync();
    String sha = scriptSha;
    try {
      if (sha == null) {
        synchronized (this) {
          sha = scriptSha;
          if (sha == null) {
            sha = commands.scriptLoad(APPEND_AND_LOAD_SCRIPT);
            scriptSha = sha;
          }
        }
      }
      return commands.evalsha(
          sha, ScriptOutputType.MULTI, new String[] {key}, fact, maxSize, timeout);
    } catch (RedisNoScriptException exception) {
      return commands.eval(
          APPEND_AND_LOAD_SCRIPT,
          ScriptOutputType.MULTI,
          new String[] {key},
          fact,
          maxSize,
          timeout);
    }
  }

  private Map<String, Object> readFact(String serialized) {
    try {
      return objectMapper.readValue(serialized, FACT_TYPE);
    } catch (Exception exception) {
      throw new IllegalStateException("Valkey contained an invalid fact", exception);
    }
  }

  private static String key(String ruleset, long version, String sessionKey) {
    return "decision-engine:fact-window:" + ruleset + ":" + version + ":" + sessionKey;
  }

  long ttlSeconds(String ruleset, long version, String sessionKey) {
    return connection.sync().ttl(key(ruleset, version, sessionKey));
  }

  @Override
  public void close() {
    connection.close();
    client.shutdown();
  }
}
