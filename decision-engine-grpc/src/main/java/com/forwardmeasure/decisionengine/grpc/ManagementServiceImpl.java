/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc;

import com.forwardmeasure.decisionengine.contract.v1.ActivateRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.CreateRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.DeleteRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.DeleteRulesetVersionResponse;
import com.forwardmeasure.decisionengine.contract.v1.GetActiveRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.ListRulesetVersionsRequest;
import com.forwardmeasure.decisionengine.contract.v1.ListRulesetVersionsResponse;
import com.forwardmeasure.decisionengine.contract.v1.RulesetManagementServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.RulesetMode;
import com.forwardmeasure.decisionengine.core.DrlCompilationException;
import com.forwardmeasure.decisionengine.domain.RulesetVersion;
import com.forwardmeasure.decisionengine.jpa.application.RulesetVersionService;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;

public class ManagementServiceImpl
    extends RulesetManagementServiceGrpc.RulesetManagementServiceImplBase {
  private final RulesetVersionService service;

  public ManagementServiceImpl(RulesetVersionService service) {
    this.service = service;
  }

  @Override
  public void createRulesetVersion(
      CreateRulesetVersionRequest request,
      StreamObserver<com.forwardmeasure.decisionengine.contract.v1.RulesetVersion> observer) {
    try {
      observer.onNext(
          wire(
              service.create(
                  request.getRuleset(),
                  request.getDrl(),
                  mode(request.getMode()),
                  request.getMaxWindowSize(),
                  request.getIdleTimeoutSeconds(),
                  request.getCreatedBy(),
                  request.getActivate())));
      observer.onCompleted();
    } catch (RuntimeException exception) {
      fail(observer, exception);
    }
  }

  @Override
  public void getActiveRulesetVersion(
      GetActiveRulesetVersionRequest request,
      StreamObserver<com.forwardmeasure.decisionengine.contract.v1.RulesetVersion> observer) {
    try {
      observer.onNext(wire(service.getActive(request.getRuleset())));
      observer.onCompleted();
    } catch (RuntimeException exception) {
      fail(observer, exception);
    }
  }

  @Override
  public void listRulesetVersions(
      ListRulesetVersionsRequest request, StreamObserver<ListRulesetVersionsResponse> observer) {
    try {
      List<RulesetVersion> items =
          service.list(request.getRuleset(), request.getCursor(), request.getLimit());
      var builder =
          ListRulesetVersionsResponse.newBuilder()
              .addAllItems(items.stream().map(ManagementServiceImpl::wire).toList());
      if (!items.isEmpty() && request.getLimit() > 0 && items.size() == request.getLimit())
        builder.setNextCursor(Long.toString(items.get(items.size() - 1).version()));
      observer.onNext(builder.build());
      observer.onCompleted();
    } catch (RuntimeException exception) {
      fail(observer, exception);
    }
  }

  @Override
  public void activateRulesetVersion(
      ActivateRulesetVersionRequest request,
      StreamObserver<com.forwardmeasure.decisionengine.contract.v1.RulesetVersion> observer) {
    try {
      observer.onNext(wire(service.activate(request.getRuleset(), request.getVersion())));
      observer.onCompleted();
    } catch (RuntimeException exception) {
      fail(observer, exception);
    }
  }

  @Override
  public void deleteRulesetVersion(
      DeleteRulesetVersionRequest request, StreamObserver<DeleteRulesetVersionResponse> observer) {
    try {
      observer.onNext(
          DeleteRulesetVersionResponse.newBuilder()
              .setDeleted(service.delete(request.getRuleset(), request.getVersion()))
              .build());
      observer.onCompleted();
    } catch (RuntimeException exception) {
      fail(observer, exception);
    }
  }

  private static com.forwardmeasure.decisionengine.domain.RulesetMode mode(
      com.forwardmeasure.decisionengine.contract.v1.RulesetMode mode) {
    return switch (mode) {
      case STATELESS -> com.forwardmeasure.decisionengine.domain.RulesetMode.STATELESS;
      case STATEFUL -> com.forwardmeasure.decisionengine.domain.RulesetMode.STATEFUL;
      default -> throw new IllegalArgumentException("ruleset mode is required");
    };
  }

  private static com.forwardmeasure.decisionengine.contract.v1.RulesetVersion wire(
      RulesetVersion value) {
    var builder =
        com.forwardmeasure.decisionengine.contract.v1.RulesetVersion.newBuilder()
            .setRuleset(value.ruleset())
            .setVersion(value.version())
            .setDrl(value.drl())
            .setActive(value.active())
            .setMode(
                value.mode() == com.forwardmeasure.decisionengine.domain.RulesetMode.STATEFUL
                    ? RulesetMode.STATEFUL
                    : RulesetMode.STATELESS)
            .setMaxWindowSize(value.maxWindowSize())
            .setIdleTimeoutSeconds(value.idleTimeoutSeconds())
            .setCreatedBy(value.createdBy() == null ? "" : value.createdBy());
    if (value.createdAt() != null)
      builder.setCreatedAt(
          Timestamp.newBuilder()
              .setSeconds(value.createdAt().getEpochSecond())
              .setNanos(value.createdAt().getNano()));
    return builder.build();
  }

  private static void fail(StreamObserver<?> observer, RuntimeException exception) {
    Status status =
        exception instanceof DrlCompilationException
            ? Status.INVALID_ARGUMENT
            : exception instanceof com.forwardmeasure.decisionengine.domain.RulesetNotFoundException
                ? Status.NOT_FOUND
                : exception instanceof IllegalStateException
                    ? Status.FAILED_PRECONDITION
                    : exception instanceof IllegalArgumentException
                        ? Status.INVALID_ARGUMENT
                        : Status.INTERNAL;
    observer.onError(
        status.withDescription(exception.getMessage()).withCause(exception).asRuntimeException());
  }
}
