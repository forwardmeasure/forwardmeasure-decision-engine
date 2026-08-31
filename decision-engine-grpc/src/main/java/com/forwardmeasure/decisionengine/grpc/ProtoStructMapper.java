/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProtoStructMapper {
  private ProtoStructMapper() {}

  static Map<String, Object> toMap(Struct struct) {
    Map<String, Object> result = new LinkedHashMap<>();
    struct.getFieldsMap().forEach((key, value) -> result.put(key, toObject(value)));
    return result;
  }

  static Struct toStruct(Map<String, Object> map) {
    Struct.Builder builder = Struct.newBuilder();
    map.forEach((key, value) -> builder.putFields(key, toValue(value)));
    return builder.build();
  }

  private static Object toObject(Value value) {
    return switch (value.getKindCase()) {
      case NULL_VALUE, KIND_NOT_SET -> null;
      case NUMBER_VALUE -> value.getNumberValue();
      case STRING_VALUE -> value.getStringValue();
      case BOOL_VALUE -> value.getBoolValue();
      case STRUCT_VALUE -> toMap(value.getStructValue());
      case LIST_VALUE ->
          value.getListValue().getValuesList().stream().map(ProtoStructMapper::toObject).toList();
    };
  }

  private static Value toValue(Object value) {
    if (value == null) return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
    if (value instanceof String text) return Value.newBuilder().setStringValue(text).build();
    if (value instanceof Boolean bool) return Value.newBuilder().setBoolValue(bool).build();
    if (value instanceof Number number)
      return Value.newBuilder().setNumberValue(number.doubleValue()).build();
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> converted = new LinkedHashMap<>();
      map.forEach(
          (key, item) -> {
            if (!(key instanceof String stringKey))
              throw new IllegalArgumentException("Struct map keys must be strings");
            converted.put(stringKey, item);
          });
      return Value.newBuilder().setStructValue(toStruct(converted)).build();
    }
    if (value instanceof Iterable<?> iterable) {
      List<Value> values = new ArrayList<>();
      iterable.forEach(item -> values.add(toValue(item)));
      return Value.newBuilder().setListValue(ListValue.newBuilder().addAllValues(values)).build();
    }
    throw new IllegalArgumentException(
        "value cannot be represented as protobuf Struct: " + value.getClass().getName());
  }
}
