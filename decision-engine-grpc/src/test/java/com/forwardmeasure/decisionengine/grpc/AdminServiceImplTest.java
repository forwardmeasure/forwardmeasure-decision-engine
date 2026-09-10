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
import com.forwardmeasure.decisionengine.core.CacheStatus;
import com.forwardmeasure.decisionengine.core.RuleEngineAdmin;
import com.forwardmeasure.decisionengine.core.RuntimeStatistics;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminServiceImplTest {

  @Test
  void exposesStatisticsAndCacheOperationsThroughGeneratedService() {
    RecordingAdmin admin = new RecordingAdmin();
    AdminServiceImpl service = new AdminServiceImpl(admin);

    CapturingObserver<com.forwardmeasure.decisionengine.contract.v1.RuntimeStatistics> statistics =
        new CapturingObserver<>();
    service.getStatistics(GetStatisticsRequest.newBuilder().build(), statistics);
    assertEquals(7, statistics.value.getInvocationCount());
    assertEquals(3, statistics.value.getCacheHitCount());
    assertNotNull(statistics.completed);

    CapturingObserver<com.forwardmeasure.decisionengine.contract.v1.CacheStatus> cache =
        new CapturingObserver<>();
    service.getCacheStatus(GetCacheStatusRequest.newBuilder().build(), cache);
    assertEquals(2, cache.value.getSize());
    assertEquals("golden/paymentsRisk", cache.value.getEntries(0).getRuleset());

    service.unloadRuleset(
        UnloadRulesetRequest.newBuilder().setRuleset("golden/paymentsRisk").setVersion(1).build(),
        new CapturingObserver<>());
    service.clearCompiledRulesCache(
        ClearCompiledRulesCacheRequest.newBuilder().build(), new CapturingObserver<>());
    service.warmRuleset(
        WarmRulesetRequest.newBuilder().setRuleset("golden/paymentsRisk").setVersion(1).build(),
        new CapturingObserver<>());

    assertEquals(1, admin.unloadCalls);
    assertEquals(1, admin.clearCalls);
    assertEquals(1, admin.warmCalls);
  }

  private static final class RecordingAdmin implements RuleEngineAdmin {
    private int unloadCalls;
    private int clearCalls;
    private int warmCalls;

    @Override
    public RuntimeStatistics statistics() {
      return new RuntimeStatistics(7, 6, 1, 3, 2, 1, 100, 20);
    }

    @Override
    public CacheStatus cacheStatus() {
      return new CacheStatus(
          100,
          2,
          List.of(
              new CacheStatus.CacheEntry("golden/paymentsRisk", 1),
              new CacheStatus.CacheEntry("golden/customerSegmentation", 1)));
    }

    @Override
    public boolean unload(String ruleset, long version) {
      unloadCalls++;
      return true;
    }

    @Override
    public void clearCache() {
      clearCalls++;
    }

    @Override
    public CacheStatus warm(String ruleset, long version) {
      warmCalls++;
      return cacheStatus();
    }
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
