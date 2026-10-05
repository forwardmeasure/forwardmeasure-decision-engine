/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.domain.RuleEvaluationException;
import com.forwardmeasure.decisionengine.domain.RulesetSource;
import com.forwardmeasure.decisionengine.domain.RulesetVersion;
import com.forwardmeasure.decisionengine.grpc.tenancy.TenantContext;
import com.forwardmeasure.decisionengine.grpc.tenancy.TenantExecution;
import com.forwardmeasure.decisionengine.grpc.tenancy.TenantScopedRuleEvaluators;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.jpa.tenancy.ThreadBoundTenantScope;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EvaluationServiceImplTest {
  @Test
  void mapsMissingOutcomeToFailedPrecondition() {
    var failure =
        new RuleEvaluationException(
            "golden/paymentsRisk",
            1,
            RuleEvaluationException.Reason.MISSING_OUTCOME,
            "rules did not produce an outcome");
    RulesetSource throwingSource =
        new RulesetSource() {
          @Override
          public RulesetVersion getActiveVersion(String ruleset) {
            throw failure;
          }

          @Override
          public RulesetVersion getVersion(String ruleset, long version) {
            throw failure;
          }
        };
    TenantId tenantId = new TenantId(UUID.randomUUID());
    var service =
        new EvaluationServiceImpl(
            new TenantScopedRuleEvaluators(throwingSource, null),
            new TenantExecution(new ThreadBoundTenantScope()));
    var observed = new AtomicReference<Throwable>();

    Context.current()
        .withValue(TenantContext.KEY, tenantId)
        .run(
            () ->
                service.evaluate(
                    EvaluationRequest.newBuilder()
                        .setRuleset("golden/paymentsRisk")
                        .setInput(com.google.protobuf.Struct.getDefaultInstance())
                        .build(),
                    new StreamObserver<>() {
                      @Override
                      public void onNext(
                          com.forwardmeasure.decisionengine.contract.v1.EvaluationResponse value) {}

                      @Override
                      public void onError(Throwable throwable) {
                        observed.set(throwable);
                      }

                      @Override
                      public void onCompleted() {}
                    }));

    assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(observed.get()).getCode());
  }
}
