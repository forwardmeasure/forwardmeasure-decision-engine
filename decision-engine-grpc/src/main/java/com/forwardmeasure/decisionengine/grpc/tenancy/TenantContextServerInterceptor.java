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

  private final TenantIdResolver resolver;

  public TenantContextServerInterceptor(TenantIdResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    LOG.info("Tenant resolution active via {}", resolver.getClass().getSimpleName());
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (HEALTH_SERVICE.equals(call.getMethodDescriptor().getServiceName())) {
      return next.startCall(call, headers);
    }
    String method = call.getMethodDescriptor().getFullMethodName();
    TenantId tenantId;
    try {
      tenantId = resolver.resolve(headers);
    } catch (TenantResolutionException failure) {
      LOG.warn(
          "Rejecting {} - tenant resolution failed: {}", method, failure.getMessage(), failure);
      call.close(
          Status.UNAUTHENTICATED.withDescription(failure.getMessage()).withCause(failure),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }
    LOG.debug("Resolved {} to tenant {}", method, tenantId);
    Context context = Context.current().withValue(TenantContext.KEY, tenantId);
    return Contexts.interceptCall(context, call, headers, next);
  }
}
