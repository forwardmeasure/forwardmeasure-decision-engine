/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc.tenancy;

import com.forwardmeasure.decisionengine.core.DrlCompiler;
import com.forwardmeasure.decisionengine.core.DroolsRuleEvaluator;
import com.forwardmeasure.decisionengine.domain.FactWindowStore;
import com.forwardmeasure.decisionengine.domain.RulesetSource;
import com.forwardmeasure.jpa.tenancy.TenantId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One {@link DroolsRuleEvaluator} per tenant, lazily created and cached - not a single shared
 * instance. {@code RulesetVersionService}/{@code RulesetSource} underneath are already correctly
 * tenant-routed per call (Hibernate's own multi-tenant connection provider resolves the right
 * tenant database from {@link com.forwardmeasure.jpa.tenancy.TenantScope} at session-open time),
 * but {@code DroolsRuleEvaluator}'s own in-memory compiled-rules cache is keyed only by {@code
 * (ruleset, version)} - with a single shared instance, two tenants whose ruleset happens to share a
 * name (a real possibility: ruleset names have never been required to be globally unique, and
 * physical database-per-tenant isolation is now what enforces separation, not naming) would
 * silently serve each other's compiled rules on a cache hit. Mirrors this session's own established
 * "one per-tenant resource instance, centrally cached" pattern (e.g. {@code
 * TenantDataSourceRegistry}) rather than teaching {@code DroolsRuleEvaluator} itself about tenancy.
 */
public final class TenantScopedRuleEvaluators {
  private static final Logger LOG = LoggerFactory.getLogger(TenantScopedRuleEvaluators.class);

  private final RulesetSource rulesetSource;
  private final FactWindowStore factWindowStore;
  private final ConcurrentHashMap<TenantId, DroolsRuleEvaluator> evaluators =
      new ConcurrentHashMap<>();

  public TenantScopedRuleEvaluators(RulesetSource rulesetSource, FactWindowStore factWindowStore) {
    this.rulesetSource = Objects.requireNonNull(rulesetSource, "rulesetSource");
    this.factWindowStore = factWindowStore;
  }

  public DroolsRuleEvaluator forTenant(TenantId tenantId) {
    Objects.requireNonNull(tenantId, "tenantId");
    return evaluators.computeIfAbsent(
        tenantId,
        id -> {
          LOG.info(
              "Creating compiled-rules cache for tenant {} (cache capacity {})",
              id,
              DroolsRuleEvaluator.DEFAULT_CACHE_CAPACITY);
          return new DroolsRuleEvaluator(
              rulesetSource,
              factWindowStore,
              new DrlCompiler(),
              DroolsRuleEvaluator.DEFAULT_CACHE_CAPACITY);
        });
  }
}
