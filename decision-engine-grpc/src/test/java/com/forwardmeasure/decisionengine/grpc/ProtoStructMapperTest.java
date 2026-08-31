/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtoStructMapperTest {
  @Test
  void convertsNestedStructsListsAndScalarsWithoutLosingShape() {
    Struct input =
        Struct.newBuilder()
            .putFields("name", Value.newBuilder().setStringValue("alice").build())
            .putFields("enabled", Value.newBuilder().setBoolValue(true).build())
            .putFields(
                "attributes",
                Value.newBuilder()
                    .setStructValue(
                        Struct.newBuilder()
                            .putFields("score", Value.newBuilder().setNumberValue(4.5).build())
                            .build())
                    .build())
            .build();

    Map<String, Object> result = ProtoStructMapper.toMap(input);

    assertEquals("alice", result.get("name"));
    assertEquals(true, result.get("enabled"));
    assertEquals(Map.of("score", 4.5), result.get("attributes"));
    assertEquals(input, ProtoStructMapper.toStruct(result));
  }
}
