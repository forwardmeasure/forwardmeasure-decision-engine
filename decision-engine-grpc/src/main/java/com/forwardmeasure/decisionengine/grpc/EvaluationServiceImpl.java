/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc;

import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationResponse;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationServiceGrpc;
import com.forwardmeasure.decisionengine.domain.EvaluationInput;
import com.forwardmeasure.decisionengine.domain.EvaluationOutcome;
import com.forwardmeasure.decisionengine.domain.RuleEvaluationException;
import com.forwardmeasure.decisionengine.domain.RuleEvaluator;
import com.forwardmeasure.decisionengine.domain.RulesetNotFoundException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.regex.Pattern;
import org.slf4j.MDC;

public class EvaluationServiceImpl extends EvaluationServiceGrpc.EvaluationServiceImplBase {
  private static final Pattern RULESET_PATTERN =
      Pattern.compile("^[a-z][a-zA-Z0-9]*(/[a-z][a-zA-Z0-9]*)*$");
  private final RuleEvaluator evaluator;

  public EvaluationServiceImpl(RuleEvaluator evaluator) {
    this.evaluator = evaluator;
  }

  @Override
  public void evaluate(EvaluationRequest request, StreamObserver<EvaluationResponse> observer) {
    String correlationId = request.getCorrelationId();
    if (correlationId != null && !correlationId.isBlank()) MDC.put("correlation_id", correlationId);
    try {
      if (request.getRuleset().isBlank() || !request.hasInput()) {
        throw Status.INVALID_ARGUMENT
            .withDescription("ruleset and input are required")
            .asRuntimeException();
      }
      if (!RULESET_PATTERN.matcher(request.getRuleset()).matches()) {
        throw Status.INVALID_ARGUMENT.withDescription("invalid ruleset name").asRuntimeException();
      }
      EvaluationOutcome outcome =
          evaluator.evaluate(
              new EvaluationInput(
                  request.getRuleset(),
                  request.hasRulesetVersion() ? request.getRulesetVersion() : null,
                  ProtoStructMapper.toMap(request.getInput()),
                  request.getSessionKey()));
      com.google.protobuf.Struct result;
      try {
        result = ProtoStructMapper.toStruct(outcome.result());
      } catch (IllegalArgumentException exception) {
        throw new RuleEvaluationException(
            request.getRuleset(),
            outcome.rulesetVersion(),
            RuleEvaluationException.Reason.MISSING_OUTCOME,
            "evaluation result cannot be represented as protobuf Struct",
            exception);
      }
      observer.onNext(
          EvaluationResponse.newBuilder()
              .setResult(result)
              .addAllFiredRules(outcome.firedRules())
              .setRulesetVersion(outcome.rulesetVersion())
              .setFactsConsidered(outcome.factsConsidered())
              .setCorrelationId(correlationId)
              .build());
      observer.onCompleted();
    } catch (RulesetNotFoundException exception) {
      observer.onError(
          Status.NOT_FOUND.withDescription(exception.getMessage()).asRuntimeException());
    } catch (RuleEvaluationException exception) {
      observer.onError(map(exception));
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription(exception.getMessage()).asRuntimeException());
    } catch (io.grpc.StatusRuntimeException exception) {
      observer.onError(exception);
    } catch (RuntimeException exception) {
      observer.onError(
          Status.INTERNAL
              .withDescription("unexpected evaluation failure")
              .withCause(exception)
              .asRuntimeException());
    } finally {
      if (correlationId != null && !correlationId.isBlank()) MDC.remove("correlation_id");
    }
  }

  private static io.grpc.StatusRuntimeException map(RuleEvaluationException exception) {
    Status status =
        switch (exception.reason()) {
          case INVALID_SESSION_KEY -> Status.INVALID_ARGUMENT;
          case MISSING_OUTCOME -> Status.FAILED_PRECONDITION;
          case FACT_WINDOW_UNAVAILABLE -> Status.UNAVAILABLE;
          case EVALUATION_FAILURE -> Status.INTERNAL;
        };
    return status.withDescription(exception.getMessage()).withCause(exception).asRuntimeException();
  }
}
