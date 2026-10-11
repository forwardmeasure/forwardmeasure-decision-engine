/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtoStructMapperTest {
  @Test
  void convertsNestedStructsAndScalarsWithoutLosingShape() {
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

  @Test
  void preservesNullsAndHeterogeneousNestedLists() {
    var values = new java.util.ArrayList<Object>();
    values.add(null);
    values.add(false);
    values.add(2.5);
    values.add("\u03a9");
    values.add(Map.of("child", java.util.List.of("first", "second")));
    values.add(java.util.List.of());
    Map<String, Object> original = Map.of("items", values, "emptyObject", Map.of());
    assertEquals(original, ProtoStructMapper.toMap(ProtoStructMapper.toStruct(original)));
  }

  @Test
  void anUnsetProtobufValueNormalizesToExplicitNullWithoutDroppingTheField() {
    var input = Struct.newBuilder().putFields("present", Value.getDefaultInstance()).build();
    var expected = new java.util.LinkedHashMap<String, Object>();
    expected.put("present", null);
    assertEquals(expected, ProtoStructMapper.toMap(input));
    assertEquals(
        Value.KindCase.NULL_VALUE,
        ProtoStructMapper.toStruct(expected).getFieldsOrThrow("present").getKindCase());
  }

  @Test
  void rejectsNonStringMapKeysAndUnsupportedNestedObjectsInsteadOfStringifyingThem() {
    assertEquals(
        "Struct map keys must be strings",
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtoStructMapper.toStruct(Map.of("nested", Map.of(7, "value"))))
            .getMessage());
    assertEquals(
        "value cannot be represented as protobuf Struct: java.lang.Object",
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtoStructMapper.toStruct(Map.of("items", java.util.List.of(new Object()))))
            .getMessage());
  }
}
