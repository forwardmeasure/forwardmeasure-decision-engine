/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc.tenancy;

import com.forwardmeasure.jpa.tenancy.TenantId;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global gRPC interceptor: resolves the calling tenant via an injected {@link TenantIdResolver} and
 * attaches it to {@link TenantContext#KEY} for the duration of the call, rejecting cleanly with
 * {@code UNAUTHENTICATED} if resolution fails. Deliberately does NOT open {@link
 * com.forwardmeasure.jpa.tenancy.TenantScope} here - {@code Scope} is {@code AutoCloseable} but
 * {@code ThreadLocal}-backed, and this method returns (closing any such scope immediately) long
 * before the actual service method body runs on whatever thread the framework dispatches it to
 * (e.g. Quarkus's {@code @Blocking} hops to a worker thread). Uses {@code Contexts.interceptCall} -
 * grpc-java's own mechanism for propagating a {@code Context} value correctly across exactly that
 * kind of hop - and leaves opening the actual (thread-bound) tenant scope to {@link
 * TenantExecution}, called synchronously from inside each service method.
 */
public class TenantContextServerInterceptor implements ServerInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(TenantContextServerInterceptor.class);

  /**
   * The chart's readiness/liveness probes are gRPC health checks, and kubelet sends no metadata -
   * so health needs no tenant, the way an HTTP service leaves its health endpoint open. It carries
   * no tenant data. Same on every framework: each registers this interceptor globally.
   */
  static final String HEALTH_SERVICE = "grpc.health.v1.Health";

  private final java.util.concurrent.atomic.AtomicBoolean checkingReadiness =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final TenantIdResolver resolver;
  private final java.util.function.BooleanSupplier readiness;

  public TenantContextServerInterceptor(TenantIdResolver resolver) {
    this(resolver, null);
  }

  public TenantContextServerInterceptor(
      TenantIdResolver resolver, java.util.function.BooleanSupplier readiness) {
    this.readiness = readiness;
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    LOG.info("Tenant resolution active via {}", resolver.getClass().getSimpleName());
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (readiness != null
        && (HEALTH_SERVICE + "/Check").equals(call.getMethodDescriptor().getFullMethodName())) {
      return health(call);
    }
    if (HEALTH_SERVICE.equals(call.getMethodDescriptor().getServiceName())) {
      return next.startCall(call, headers);
    }
    String method = call.getMethodDescriptor().getFullMethodName();
    TenantId tenantId;
    com.forwardmeasure.authzen.ActiveOrganization organization = null;
    try {
      organization = resolver.resolveOrganization(headers);
      tenantId = organization == null ? resolver.resolve(headers) : organization.tenantId();
    } catch (TenantResolutionException failure) {
      LOG.warn(
          "Rejecting {} - tenant resolution failed: {}", method, failure.getMessage(), failure);
      call.close(
          Status.UNAUTHENTICATED.withDescription(failure.getMessage()).withCause(failure),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }
    LOG.debug("Resolved {} to tenant {}", method, tenantId);
    Context context =
        Context.current()
            .withValues(
                TenantContext.KEY,
                tenantId,
                TenantContext.ORGANIZATION,
                organization,
                TenantContext.METHOD,
                method);
    return Contexts.interceptCall(context, call, headers, next);
  }

  /** Kubelet's readiness is distinct from liveness and checks real dependencies. */
  private <ReqT, RespT> ServerCall.Listener<ReqT> health(ServerCall<ReqT, RespT> call) {
    call.request(1);
    return new ServerCall.Listener<>() {
      private String service;
      private boolean invalidRequest;

      @Override
      public void onMessage(ReqT message) {
        // Quarkus and grpc-services generate different Java classes for the same health
        // protocol. Use the registered marshallers at the boundary, never a Java type cast.
        try (var wire = call.getMethodDescriptor().getRequestMarshaller().stream(message)) {
          service = io.grpc.health.v1.HealthCheckRequest.parseFrom(wire).getService();
        } catch (IOException malformed) {
          invalidRequest = true;
          call.close(
              Status.INVALID_ARGUMENT.withDescription("Invalid health request"), new Metadata());
        }
      }

      @Override
      public void onHalfClose() {
        if (invalidRequest) return;
        if (service == null) {
          call.close(
              Status.INVALID_ARGUMENT.withDescription("Missing health request"), new Metadata());
          return;
        }
        if (!"readiness".equals(service) && !"liveness".equals(service) && !"".equals(service)) {
          call.close(Status.NOT_FOUND, new Metadata());
          return;
        }
        if ("liveness".equals(service)) {
          reply(true);
        } else if (checkingReadiness.compareAndSet(false, true)) {
          // Dependency I/O must never block Quarkus's transport/event-loop thread. At most one
          // dependency probe is in flight even when callers time out before a pool does.
          Thread.startVirtualThread(
              () -> {
                boolean serving;
                try {
                  serving = readiness.getAsBoolean();
                } catch (RuntimeException unavailable) {
                  serving = false;
                } finally {
                  checkingReadiness.set(false);
                }
                reply(serving);
              });
        } else {
          reply(false);
        }
      }

      private void reply(boolean serving) {
        if (call.isCancelled()) return;
        var response =
            io.grpc.health.v1.HealthCheckResponse.newBuilder()
                .setStatus(
                    serving
                        ? io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING
                        : io.grpc.health.v1.HealthCheckResponse.ServingStatus.NOT_SERVING)
                .build();
        RespT frameworkResponse =
            call.getMethodDescriptor()
                .getResponseMarshaller()
                .parse(new ByteArrayInputStream(response.toByteArray()));
        call.sendHeaders(new Metadata());
        call.sendMessage(frameworkResponse);
        call.close(Status.OK, new Metadata());
      }
    };
  }

  public static boolean dependenciesHealthy(
      javax.sql.DataSource controlPlane, java.util.function.BooleanSupplier store) {
    try (var connection = controlPlane.getConnection()) {
      return connection.isValid(2) && store.getAsBoolean();
    } catch (java.sql.SQLException unavailable) {
      return false;
    }
  }
}
