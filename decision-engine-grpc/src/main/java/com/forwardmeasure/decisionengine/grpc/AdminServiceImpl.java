/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc;

import com.forwardmeasure.decisionengine.contract.v1.CacheEntry;
import com.forwardmeasure.decisionengine.contract.v1.CacheStatus;
import com.forwardmeasure.decisionengine.contract.v1.ClearCompiledRulesCacheRequest;
import com.forwardmeasure.decisionengine.contract.v1.DecisionEngineAdminServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.GetCacheStatusRequest;
import com.forwardmeasure.decisionengine.contract.v1.GetStatisticsRequest;
import com.forwardmeasure.decisionengine.contract.v1.RuntimeStatistics;
import com.forwardmeasure.decisionengine.contract.v1.UnloadRulesetRequest;
import com.forwardmeasure.decisionengine.contract.v1.WarmRulesetRequest;
import com.forwardmeasure.decisionengine.core.RuleEngineAdmin;
import com.forwardmeasure.decisionengine.domain.RulesetNotFoundException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/** Framework-neutral administrative service for local evaluator operations. */
public class AdminServiceImpl
    extends DecisionEngineAdminServiceGrpc.DecisionEngineAdminServiceImplBase {
  private final RuleEngineAdmin admin;

  public AdminServiceImpl(RuleEngineAdmin admin) {
    this.admin = admin;
  }

  @Override
  public void getStatistics(
      GetStatisticsRequest request, StreamObserver<RuntimeStatistics> observer) {
    try {
      var value = admin.statistics();
      observer.onNext(
          RuntimeStatistics.newBuilder()
              .setInvocationCount(value.invocationCount())
              .setSuccessCount(value.successCount())
              .setFailureCount(value.failureCount())
              .setCacheHitCount(value.cacheHitCount())
              .setCacheMissCount(value.cacheMissCount())
              .setCacheEvictionCount(value.cacheEvictionCount())
              .setTotalEvaluationNanos(value.totalEvaluationNanos())
              .setTotalCompilationNanos(value.totalCompilationNanos())
              .build());
      observer.onCompleted();
    } catch (RuntimeException exception) {
      fail(observer, exception);
    }
  }

  @Override
  public void getCacheStatus(GetCacheStatusRequest request, StreamObserver<CacheStatus> observer) {
    respondCache(observer, admin.cacheStatus());
  }

  @Override
  public void unloadRuleset(UnloadRulesetRequest request, StreamObserver<CacheStatus> observer) {
    if (request.getRuleset().isBlank() || request.getVersion() <= 0) {
      fail(observer, new IllegalArgumentException("ruleset and positive version are required"));
      return;
    }
    try {
      admin.unload(request.getRuleset(), request.getVersion());
      respondCache(observer, admin.cacheStatus());
    } catch (RuntimeException exception) {
      fail(observer, exception);
    }
  }

  @Override
  public void clearCompiledRulesCache(
      ClearCompiledRulesCacheRequest request, StreamObserver<CacheStatus> observer) {
    try {
      admin.clearCache();
      respondCache(observer, admin.cacheStatus());
    } catch (RuntimeException exception) {
      fail(observer, exception);
    }
  }

  @Override
  public void warmRuleset(WarmRulesetRequest request, StreamObserver<CacheStatus> observer) {
    if (request.getRuleset().isBlank() || request.getVersion() <= 0) {
      fail(observer, new IllegalArgumentException("ruleset and positive version are required"));
      return;
    }
    try {
      respondCache(observer, admin.warm(request.getRuleset(), request.getVersion()));
    } catch (RuntimeException exception) {
      fail(observer, exception);
    }
  }

  private static void respondCache(
      StreamObserver<CacheStatus> observer,
      com.forwardmeasure.decisionengine.core.CacheStatus value) {
    observer.onNext(
        CacheStatus.newBuilder()
            .setCapacity(value.capacity())
            .setSize(value.size())
            .addAllEntries(value.entries().stream().map(AdminServiceImpl::entry).toList())
            .build());
    observer.onCompleted();
  }

  private static CacheEntry entry(
      com.forwardmeasure.decisionengine.core.CacheStatus.CacheEntry value) {
    return CacheEntry.newBuilder().setRuleset(value.ruleset()).setVersion(value.version()).build();
  }

  private static void fail(StreamObserver<?> observer, RuntimeException exception) {
    Status status =
        exception instanceof RulesetNotFoundException
            ? Status.NOT_FOUND
            : exception instanceof IllegalArgumentException
                ? Status.INVALID_ARGUMENT
                : Status.INTERNAL;
    observer.onError(
        status.withDescription(exception.getMessage()).withCause(exception).asRuntimeException());
  }
}
