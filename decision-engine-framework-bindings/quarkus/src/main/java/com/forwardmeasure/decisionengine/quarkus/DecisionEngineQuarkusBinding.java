/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.decisionengine.contract.v1.ActivateRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.CreateRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.DeleteRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.DeleteRulesetVersionResponse;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationResponse;
import com.forwardmeasure.decisionengine.contract.v1.GetActiveRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.ListRulesetVersionsRequest;
import com.forwardmeasure.decisionengine.contract.v1.ListRulesetVersionsResponse;
import com.forwardmeasure.decisionengine.core.DrlCompiler;
import com.forwardmeasure.decisionengine.core.DroolsRuleEvaluator;
import com.forwardmeasure.decisionengine.domain.RuleEvaluator;
import com.forwardmeasure.decisionengine.domain.RulesetSource;
import com.forwardmeasure.decisionengine.factwindow.ValkeyFactWindowStore;
import com.forwardmeasure.decisionengine.grpc.EvaluationServiceImpl;
import com.forwardmeasure.decisionengine.grpc.ManagementServiceImpl;
import com.forwardmeasure.decisionengine.jpa.application.RulesetVersionService;
import com.forwardmeasure.decisionengine.jpa.repository.RulesetVersionRepository;
import com.forwardmeasure.decisionengine.jpa.service.JpaRulesetSource;
import com.forwardmeasure.decisionengine.jpa.service.RulesetVersionServiceImpl;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DecisionEngineQuarkusBinding {
  @Produces
  @ApplicationScoped
  RulesetVersionRepository repository(EntityManager entityManager) {
    RulesetVersionRepository repository = new RulesetVersionRepository();
    repository.bindPersistenceContext(entityManager);
    return repository;
  }

  @Produces
  @ApplicationScoped
  RulesetVersionService rulesetVersionService(RulesetVersionRepository repository) {
    return new RulesetVersionServiceImpl(repository, new DrlCompiler());
  }

  @Produces
  @ApplicationScoped
  RulesetSource rulesetSource(RulesetVersionService service) {
    return new JpaRulesetSource(service);
  }

  @Produces
  @ApplicationScoped
  ValkeyFactWindowStore factWindow(
      @ConfigProperty(name = "decision-engine.valkey.host") String host,
      @ConfigProperty(name = "decision-engine.valkey.port", defaultValue = "6379") int port,
      @ConfigProperty(name = "decision-engine.valkey.password") Optional<String> password) {
    return new ValkeyFactWindowStore(host, port, password.orElse(null), new ObjectMapper());
  }

  @Produces
  @ApplicationScoped
  RuleEvaluator evaluator(RulesetSource source, ValkeyFactWindowStore store) {
    return new DroolsRuleEvaluator(source, store);
  }

  @GrpcService
  @Blocking
  @Singleton
  public static final class EvaluationService extends EvaluationServiceImpl {
    @jakarta.inject.Inject
    public EvaluationService(RuleEvaluator evaluator) {
      super(evaluator);
    }

    @Override
    @Blocking
    public void evaluate(EvaluationRequest request, StreamObserver<EvaluationResponse> observer) {
      super.evaluate(request, observer);
    }
  }

  @GrpcService
  @Blocking
  @Singleton
  public static final class ManagementService extends ManagementServiceImpl {
    @jakarta.inject.Inject
    public ManagementService(RulesetVersionService service) {
      super(service);
    }

    @Override
    @Blocking
    @Transactional
    public void createRulesetVersion(
        CreateRulesetVersionRequest request,
        StreamObserver<com.forwardmeasure.decisionengine.contract.v1.RulesetVersion> observer) {
      super.createRulesetVersion(request, observer);
    }

    @Override
    @Blocking
    @Transactional
    public void getActiveRulesetVersion(
        GetActiveRulesetVersionRequest request,
        StreamObserver<com.forwardmeasure.decisionengine.contract.v1.RulesetVersion> observer) {
      super.getActiveRulesetVersion(request, observer);
    }

    @Override
    @Blocking
    @Transactional
    public void listRulesetVersions(
        ListRulesetVersionsRequest request, StreamObserver<ListRulesetVersionsResponse> observer) {
      super.listRulesetVersions(request, observer);
    }

    @Override
    @Blocking
    @Transactional
    public void activateRulesetVersion(
        ActivateRulesetVersionRequest request,
        StreamObserver<com.forwardmeasure.decisionengine.contract.v1.RulesetVersion> observer) {
      super.activateRulesetVersion(request, observer);
    }

    @Override
    @Blocking
    @Transactional
    public void deleteRulesetVersion(
        DeleteRulesetVersionRequest request,
        StreamObserver<DeleteRulesetVersionResponse> observer) {
      super.deleteRulesetVersion(request, observer);
    }
  }
}
