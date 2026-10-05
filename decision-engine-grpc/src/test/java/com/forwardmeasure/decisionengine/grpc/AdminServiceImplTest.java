/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.forwardmeasure.decisionengine.contract.v1.ClearCompiledRulesCacheRequest;
import com.forwardmeasure.decisionengine.contract.v1.GetCacheStatusRequest;
import com.forwardmeasure.decisionengine.contract.v1.GetStatisticsRequest;
import com.forwardmeasure.decisionengine.contract.v1.UnloadRulesetRequest;
import com.forwardmeasure.decisionengine.contract.v1.WarmRulesetRequest;
import com.forwardmeasure.decisionengine.domain.RulesetMode;
import com.forwardmeasure.decisionengine.domain.RulesetSource;
import com.forwardmeasure.decisionengine.domain.RulesetVersion;
import com.forwardmeasure.decisionengine.grpc.tenancy.TenantContext;
import com.forwardmeasure.decisionengine.grpc.tenancy.TenantExecution;
import com.forwardmeasure.decisionengine.grpc.tenancy.TenantScopedRuleEvaluators;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.jpa.tenancy.ThreadBoundTenantScope;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminServiceImplTest {
  private static final String RULESET = "golden/paymentsRisk";
  private static final String DRL =
      """
      package rules;
      global java.util.Map result;
      rule "approve"
      when
        $fact : java.util.Map( this["approved"] == true )
      then
        result.put("outcome", "approved");
      end
      """;

  @Test
  void warmingAndUnloadingAReplsetOperateOnTheCallingTenantsOwnCache() {
    TenantId tenantId = new TenantId(UUID.randomUUID());
    AdminServiceImpl service = newService(tenantId);

    Context.current()
        .withValue(TenantContext.KEY, tenantId)
        .run(
            () -> {
              CapturingObserver<com.forwardmeasure.decisionengine.contract.v1.CacheStatus> warmed =
                  new CapturingObserver<>();
              service.warmRuleset(
                  WarmRulesetRequest.newBuilder().setRuleset(RULESET).setVersion(1).build(),
                  warmed);
              assertEquals(1, warmed.value.getSize());
              assertEquals(RULESET, warmed.value.getEntries(0).getRuleset());

              CapturingObserver<com.forwardmeasure.decisionengine.contract.v1.RuntimeStatistics>
                  statistics = new CapturingObserver<>();
              service.getStatistics(GetStatisticsRequest.newBuilder().build(), statistics);
              assertEquals(1, statistics.value.getCacheMissCount());
              assertEquals(0, statistics.value.getCacheHitCount());
              assertNotNull(statistics.completed);

              CapturingObserver<com.forwardmeasure.decisionengine.contract.v1.CacheStatus>
                  afterUnload = new CapturingObserver<>();
              service.unloadRuleset(
                  UnloadRulesetRequest.newBuilder().setRuleset(RULESET).setVersion(1).build(),
                  afterUnload);
              assertEquals(0, afterUnload.value.getSize());

              service.warmRuleset(
                  WarmRulesetRequest.newBuilder().setRuleset(RULESET).setVersion(1).build(),
                  new CapturingObserver<>());
              CapturingObserver<com.forwardmeasure.decisionengine.contract.v1.CacheStatus>
                  afterClear = new CapturingObserver<>();
              service.clearCompiledRulesCache(
                  ClearCompiledRulesCacheRequest.newBuilder().build(), afterClear);
              assertEquals(0, afterClear.value.getSize());
            });
  }

  @Test
  void eachTenantHasItsOwnCacheEvenForTheSameRulesetName() {
    TenantId tenantA = new TenantId(UUID.randomUUID());
    TenantId tenantB = new TenantId(UUID.randomUUID());
    AdminServiceImpl service = newService(tenantA, tenantB);

    Context.current()
        .withValue(TenantContext.KEY, tenantA)
        .run(
            () ->
                service.warmRuleset(
                    WarmRulesetRequest.newBuilder().setRuleset(RULESET).setVersion(1).build(),
                    new CapturingObserver<>()));

    Context.current()
        .withValue(TenantContext.KEY, tenantB)
        .run(
            () -> {
              CapturingObserver<com.forwardmeasure.decisionengine.contract.v1.CacheStatus> cache =
                  new CapturingObserver<>();
              service.getCacheStatus(GetCacheStatusRequest.newBuilder().build(), cache);
              assertEquals(
                  0, cache.value.getSize(), "tenant B must not see tenant A's cached ruleset");
            });
  }

  private static AdminServiceImpl newService(TenantId... tenantIds) {
    RulesetVersion version =
        new RulesetVersion(RULESET, 1, DRL, true, RulesetMode.STATELESS, 10, 60, null, "test");
    RulesetSource source =
        new RulesetSource() {
          @Override
          public RulesetVersion getActiveVersion(String ruleset) {
            return version;
          }

          @Override
          public RulesetVersion getVersion(String ruleset, long number) {
            return version;
          }
        };
    Map<TenantId, TenantDatabase> resolutions = new HashMap<>();
    int index = 0;
    for (TenantId tenantId : tenantIds) {
      resolutions.put(tenantId, TenantDatabase.forAlias("adminservicetest" + index++));
    }
    return new AdminServiceImpl(
        new TenantScopedRuleEvaluators(source, null),
        new TenantExecution(new ThreadBoundTenantScope()));
  }

  private static final class CapturingObserver<T> implements StreamObserver<T> {
    private T value;
    private Throwable error;
    private Boolean completed;

    @Override
    public void onNext(T value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
    }

    @Override
    public void onCompleted() {
      this.completed = true;
    }
  }
}
