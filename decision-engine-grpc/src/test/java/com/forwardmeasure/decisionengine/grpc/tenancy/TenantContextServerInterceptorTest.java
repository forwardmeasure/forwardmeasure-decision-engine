/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Real interceptor, real resolver, hand-written call and handler doubles: tenant resolution guards
 * every service except gRPC health, which Kubernetes probes call without any metadata.
 */
class TenantContextServerInterceptorTest {
  private final TenantContextServerInterceptor interceptor =
      new TenantContextServerInterceptor(new TrustingMetadataTenantResolver());

  @Test
  void aHealthCheckNeedsNoTenant() {
    RecordingCall call = new RecordingCall("grpc.health.v1.Health", "Check");
    boolean[] started = {false};

    interceptor.interceptCall(
        call,
        new Metadata(),
        (handled, headers) -> {
          started[0] = true;
          return new ServerCall.Listener<>() {};
        });

    assertTrue(started[0], "the health service must be reached without tenant metadata");
    assertNull(call.closedWith);
  }

  @Test
  void anyOtherCallWithoutATenantIsUnauthenticated() {
    RecordingCall call =
        new RecordingCall("forwardmeasure.decisionengine.v1.EvaluationService", "Evaluate");
    boolean[] started = {false};

    interceptor.interceptCall(
        call,
        new Metadata(),
        (handled, headers) -> {
          started[0] = true;
          return new ServerCall.Listener<>() {};
        });

    assertFalse(started[0]);
    assertEquals(Status.Code.UNAUTHENTICATED, call.closedWith.getCode());
  }

  /** Records how the interceptor closed the call, if it did. */
  private static final class RecordingCall extends ServerCall<Object, Object> {
    private static final MethodDescriptor.Marshaller<Object> NONE =
        new MethodDescriptor.Marshaller<>() {
          @Override
          public InputStream stream(Object value) {
            return new ByteArrayInputStream(new byte[0]);
          }

          @Override
          public Object parse(InputStream stream) {
            return null;
          }
        };

    private final MethodDescriptor<Object, Object> method;
    private Status closedWith;

    RecordingCall(String service, String method) {
      this.method =
          MethodDescriptor.newBuilder(NONE, NONE)
              .setType(MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(MethodDescriptor.generateFullMethodName(service, method))
              .build();
    }

    @Override
    public void request(int numMessages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(Object message) {}

    @Override
    public void close(Status status, Metadata trailers) {
      closedWith = status;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<Object, Object> getMethodDescriptor() {
      return method;
    }
  }
}
