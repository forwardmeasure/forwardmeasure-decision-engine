/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.forwardmeasure.decisionengine.grpc.tenancy.TenantContextServerInterceptor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Exercises the real, incompatible generated health types without starting external services. */
class HealthProtocolCompatibilityTest {
  static Stream<MethodDescriptor<?, ?>> protocols() {
    return Stream.of(
        io.grpc.health.v1.HealthGrpc.getCheckMethod(), grpc.health.v1.HealthGrpc.getCheckMethod());
  }

  @ParameterizedTest
  @MethodSource("protocols")
  void livenessIgnoresUnavailableDependencies(MethodDescriptor<?, ?> protocol) throws Exception {
    AtomicInteger probes = new AtomicInteger();
    var call =
        check(
            protocol,
            "liveness",
            () -> {
              probes.incrementAndGet();
              return false;
            });
    assertServing(call, HealthCheckResponse.ServingStatus.SERVING);
    assertEquals(0, probes.get());
  }

  @ParameterizedTest
  @MethodSource("protocols")
  void readinessReportsHealthyAndUnavailableDependencies(MethodDescriptor<?, ?> protocol)
      throws Exception {
    assertServing(
        check(protocol, "readiness", () -> true), HealthCheckResponse.ServingStatus.SERVING);
    assertServing(
        check(protocol, "readiness", () -> false), HealthCheckResponse.ServingStatus.NOT_SERVING);
  }

  @ParameterizedTest
  @MethodSource("protocols")
  void dependencyExceptionReportsNotServing(MethodDescriptor<?, ?> protocol) throws Exception {
    assertServing(
        check(
            protocol,
            "readiness",
            () -> {
              throw new IllegalStateException("offline");
            }),
        HealthCheckResponse.ServingStatus.NOT_SERVING);
  }

  @ParameterizedTest
  @MethodSource("protocols")
  void emptyServiceChecksReadiness(MethodDescriptor<?, ?> protocol) throws Exception {
    assertServing(check(protocol, "", () -> false), HealthCheckResponse.ServingStatus.NOT_SERVING);
  }

  @ParameterizedTest
  @MethodSource("protocols")
  void unknownServiceIsNotFound(MethodDescriptor<?, ?> protocol) throws Exception {
    var call = check(protocol, "unregistered", () -> fail("must not probe dependencies"));
    assertEquals(Status.Code.NOT_FOUND, call.status.getCode());
    assertNull(call.response);
  }

  @ParameterizedTest
  @MethodSource("protocols")
  void missingRequestIsInvalidArgument(MethodDescriptor<?, ?> protocol) throws Exception {
    var call = check(protocol, null, () -> fail("must not probe dependencies"));
    assertEquals(Status.Code.INVALID_ARGUMENT, call.status.getCode());
    assertNull(call.response);
  }

  private static void assertServing(
      HealthCall<?, ?> call, HealthCheckResponse.ServingStatus expected) {
    assertEquals(Status.Code.OK, call.status.getCode());
    assertEquals(expected, call.response.getStatus());
    assertEquals(1, call.responses);
  }

  private static <ReqT, RespT> HealthCall<ReqT, RespT> check(
      MethodDescriptor<ReqT, RespT> protocol, String service, BooleanSupplier readiness)
      throws Exception {
    var interceptor =
        new TenantContextServerInterceptor(
            headers -> fail("health must not resolve a tenant"), readiness);
    var call = new HealthCall<>(protocol);
    var listener =
        interceptor.interceptCall(
            call,
            new Metadata(),
            (ignored, headers) -> fail("readiness-enabled interceptor must handle Check"));
    if (service != null) {
      byte[] wire = HealthCheckRequest.newBuilder().setService(service).build().toByteArray();
      listener.onMessage(protocol.getRequestMarshaller().parse(new ByteArrayInputStream(wire)));
    }
    listener.onHalfClose();
    assertTrue(call.closed.await(5, TimeUnit.SECONDS), "health response must complete");
    return call;
  }

  private static final class HealthCall<ReqT, RespT> extends ServerCall<ReqT, RespT> {
    private final MethodDescriptor<ReqT, RespT> protocol;
    private final CountDownLatch closed = new CountDownLatch(1);
    private Status status;
    private HealthCheckResponse response;
    private int responses;

    private HealthCall(MethodDescriptor<ReqT, RespT> protocol) {
      this.protocol = protocol;
    }

    @Override
    public void request(int messages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
      return protocol;
    }

    @Override
    public void sendMessage(RespT message) {
      // Serialize with the framework's actual marshaller: an incorrect response Java type must
      // fail here, just as it does in the transport. Decode its wire result independently.
      try (var wire = protocol.getResponseMarshaller().stream(message)) {
        response = HealthCheckResponse.parseFrom(wire);
        responses++;
      } catch (IOException malformed) {
        throw new AssertionError(malformed);
      }
    }

    @Override
    public void close(Status status, Metadata trailers) {
      this.status = status;
      closed.countDown();
    }
  }
}
