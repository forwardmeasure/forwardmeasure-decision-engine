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

/**
 * Carries the resolved tenant across gRPC's own executor/thread hops - deliberately {@link
 * io.grpc.Context}, not a plain {@code ThreadLocal}: {@link TenantContextServerInterceptor} runs on
 * the transport thread, but a framework's own service dispatch (e.g. Quarkus's {@code @Blocking})
 * may invoke the actual service method on a different worker thread. {@code io.grpc.Context} is
 * grpc-java's own mechanism for exactly this - correctly propagated across those hops via {@code
 * Contexts.interceptCall}, unlike a raw {@code ThreadLocal}.
 *
 * <p>{@link com.forwardmeasure.jpa.tenancy.TenantScope} itself is still {@code ThreadLocal}-based
 * (unchanged - see its own javadoc), so it must be opened synchronously, on the same thread that
 * then performs the tenant-scoped work - see {@link TenantExecution}, which reads this context and
 * opens that scope together, right before the real work runs.
 */
public final class TenantContext {
  public static final Context.Key<TenantId> KEY = Context.key("decision-engine-tenant-id");

  public static final Context.Key<com.forwardmeasure.authzen.ActiveOrganization> ORGANIZATION =
      Context.key("decision-engine-organization");
  public static final Context.Key<String> METHOD = Context.key("decision-engine-method");

  private TenantContext() {}

  /** The current call's resolved tenant, or empty if none was attached (e.g. outside an RPC). */
  public static java.util.Optional<TenantId> current() {
    return java.util.Optional.ofNullable(KEY.get());
  }

  /**
   * Same as {@link #current()}, but fails loudly - every real RPC must have gone through {@link
   * TenantContextServerInterceptor} first, so a missing tenant here is a wiring bug, not a normal
   * "no tenant" case.
   */
  public static TenantId required() {
    TenantId tenantId = KEY.get();
    if (tenantId == null) {
      throw new IllegalStateException(
          "No tenant attached to this call - TenantContextServerInterceptor must run before any"
              + " service method that needs one");
    }
    return tenantId;
  }
}
