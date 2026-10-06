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
  private enum DecisionAction implements com.forwardmeasure.authzen.Action {
    EVALUATE("decision:evaluate"),
    MANAGE("decision:manage"),
    ADMIN("decision:admin");
    private final String scope;

    DecisionAction(String scope) {
      this.scope = scope;
    }

    public String scope() {
      return scope;
    }
  }

  private final TenantScope tenantScope;
  private final com.forwardmeasure.jpa.liquibase.TenantDatabaseResolver tenants;
  private final com.forwardmeasure.authzen.AuthorizationService authorization;

  public TenantExecution(TenantScope tenantScope) {
    this(tenantScope, null, null);
  }

  public TenantExecution(
      TenantScope tenantScope,
      com.forwardmeasure.jpa.liquibase.TenantDatabaseResolver tenants,
      com.forwardmeasure.authzen.AuthorizationService authorization) {
    this.tenantScope = Objects.requireNonNull(tenantScope, "tenantScope");
    this.tenants = tenants;
    this.authorization = authorization;
    if ((tenants == null) != (authorization == null))
      throw new IllegalArgumentException("Both registry and authorization are required");
  }

  public <T> T call(Supplier<T> operation) {
    return call(TenantContext.required(), operation);
  }

  public void run(Runnable operation) {
    call(
        () -> {
          operation.run();
          return null;
        });
  }

  /** Convenience for call sites that already resolved the tenant themselves. */
  public <T> T call(TenantId tenantId, Supplier<T> operation) {
    if (io.grpc.Context.current().isCancelled())
      throw io.grpc.Status.CANCELLED.asRuntimeException();
    if (authorization != null) {
      var organization = TenantContext.ORGANIZATION.get();
      if (organization == null || !organization.tenantId().equals(tenantId)) {
        throw io.grpc.Status.UNAUTHENTICATED
            .withDescription("Verified organization required")
            .asRuntimeException();
      }
      try {
        tenants.resolve(tenantId);
      } catch (IllegalStateException inactive) {
        throw io.grpc.Status.PERMISSION_DENIED
            .withDescription("Tenant is not active")
            .asRuntimeException();
      }
      String method = TenantContext.METHOD.get();
      DecisionAction action;
      if (method != null
          && method.startsWith("forwardmeasure.decisionengine.v1.EvaluationService/"))
        action = DecisionAction.EVALUATE;
      else if (method != null
          && method.startsWith("forwardmeasure.decisionengine.v1.RulesetManagementService/"))
        action = DecisionAction.MANAGE;
      else if (method != null
          && method.startsWith("forwardmeasure.decisionengine.v1.DecisionEngineAdminService/"))
        action = DecisionAction.ADMIN;
      else
        throw io.grpc.Status.PERMISSION_DENIED
            .withDescription("Unsupported decision operation")
            .asRuntimeException();
      try {
        authorization.requireAuthorized(
            new com.forwardmeasure.authzen.AuthorizationRequest(
                organization,
                new com.forwardmeasure.authzen.AuthorizationResource(
                    "decision-engine", "rulesets", java.util.Map.of()),
                action,
                java.util.UUID.randomUUID().toString(),
                java.util.Map.of("grpcMethod", method)));
      } catch (com.forwardmeasure.authzen.AuthorizationDeniedException denied) {
        throw io.grpc.Status.PERMISSION_DENIED
            .withDescription("Decision operation denied")
            .asRuntimeException();
      } catch (com.forwardmeasure.authzen.AuthorizationUnavailableException unavailable) {
        throw io.grpc.Status.UNAVAILABLE
            .withDescription("Decision authorization unavailable")
            .asRuntimeException();
      }
    }
    return tenantScope.call(tenantId, operation);
  }
}
