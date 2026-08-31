/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.micronaut;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.forwardmeasure.jpa.tenancy.TenantSchema;
import com.forwardmeasure.jpa.tenancy.TenantScope;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;

@Factory
public class DecisionEngineMicronautBinding {
  @Singleton
  @Primary
  TenantScope publicTenantScope() {
    return new TenantScope() {
      @Override
      public java.util.Optional<TenantSchema> current() {
        return java.util.Optional.of(TenantSchema.PUBLIC);
      }

      @Override
      public Scope open(TenantSchema schema) {
        if (!TenantSchema.PUBLIC.equals(schema)) {
          throw new IllegalArgumentException("decision engine supports only the public schema");
        }
        return () -> {};
      }
    };
  }

  @Singleton
  @Requires(beans = EntityManager.class)
  RulesetVersionRepository rulesetVersionRepository(EntityManager entityManager) {
    RulesetVersionRepository repository = new RulesetVersionRepository();
    repository.bindPersistenceContext(entityManager);
    return repository;
  }

  @Singleton
  RulesetVersionService rulesetVersionService(
      RulesetVersionRepository repository, TransactionOperations<Session> transactions) {
    return MicronautTransactionalServiceProxy.create(
        RulesetVersionService.class,
        new RulesetVersionServiceImpl(repository, new DrlCompiler()),
        transactions);
  }

  @Singleton
  RulesetSource rulesetSource(RulesetVersionService service) {
    return new JpaRulesetSource(service);
  }

  @Singleton
  ValkeyFactWindowStore factWindow(
      @Value("${decision-engine.valkey.host}") String host,
      @Value("${decision-engine.valkey.port:6379}") int port,
      @Value("${decision-engine.valkey.password:}") String password) {
    return new ValkeyFactWindowStore(
        host, port, password.isBlank() ? null : password, new ObjectMapper());
  }

  @Singleton
  RuleEvaluator evaluator(RulesetSource source, ValkeyFactWindowStore store) {
    return new DroolsRuleEvaluator(source, store);
  }

  @Singleton
  public static final class MicronautEvaluationService extends EvaluationServiceImpl {
    @Inject
    public MicronautEvaluationService(RuleEvaluator evaluator) {
      super(evaluator);
    }
  }

  @Singleton
  public static final class MicronautManagementService extends ManagementServiceImpl {
    @Inject
    public MicronautManagementService(RulesetVersionService service) {
      super(service);
    }
  }
}
