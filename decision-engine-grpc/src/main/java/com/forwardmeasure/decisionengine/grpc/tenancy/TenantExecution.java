/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc.tenancy;

import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.jpa.tenancy.TenantScope;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reads the current call's tenant from {@link TenantContext} (attached earlier by {@link
 * TenantContextServerInterceptor}) and opens {@link TenantScope} around a unit of tenant-scoped
 * work, synchronously, on the calling thread - the one place {@code TenantScope}'s {@code
 * ThreadLocal} semantics are actually safe to rely on, since by the time a service method calls
 * this, framework dispatch (e.g. Quarkus's {@code @Blocking} thread hop) has already happened and
 * everything from here on runs on one thread. Every service-impl method that touches JPA-backed
 * data (directly, or transitively through {@code RulesetVersionService}/a per-tenant {@code
 * DroolsRuleEvaluator}) must route through this, not call {@code TenantScope} directly.
 *
 * <p>Database resolution belongs to the persistence connection provider.
 */
public final class TenantExecution {
  private final TenantScope tenantScope;

  public TenantExecution(TenantScope tenantScope) {
    this.tenantScope = Objects.requireNonNull(tenantScope, "tenantScope");
  }

  public <T> T call(Supplier<T> operation) {
    return call(TenantContext.required(), operation);
  }

  public void run(Runnable operation) {
    tenantScope.run(TenantContext.required(), operation);
  }

  /** Convenience for call sites that already resolved the tenant themselves. */
  public <T> T call(TenantId tenantId, Supplier<T> operation) {
    return tenantScope.call(tenantId, operation);
  }
}
