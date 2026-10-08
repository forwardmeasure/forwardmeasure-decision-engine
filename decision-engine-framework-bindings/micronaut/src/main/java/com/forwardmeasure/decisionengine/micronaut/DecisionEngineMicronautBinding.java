/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.micronaut;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.decisionengine.core.DrlCompiler;
import com.forwardmeasure.decisionengine.domain.RulesetSource;
import com.forwardmeasure.decisionengine.factwindow.ValkeyFactWindowStore;
import com.forwardmeasure.decisionengine.grpc.AdminServiceImpl;
import com.forwardmeasure.decisionengine.grpc.EvaluationServiceImpl;
import com.forwardmeasure.decisionengine.grpc.ManagementServiceImpl;
import com.forwardmeasure.decisionengine.grpc.tenancy.TenantContextServerInterceptor;
import com.forwardmeasure.decisionengine.grpc.tenancy.TenantExecution;
import com.forwardmeasure.decisionengine.grpc.tenancy.TenantIdResolver;
import com.forwardmeasure.decisionengine.grpc.tenancy.TenantScopedRuleEvaluators;
import com.forwardmeasure.decisionengine.grpc.tenancy.VerifiedJwtTenantResolver;
import com.forwardmeasure.decisionengine.jpa.application.RulesetVersionService;
import com.forwardmeasure.decisionengine.jpa.repository.RulesetVersionRepository;
import com.forwardmeasure.decisionengine.jpa.service.JpaRulesetSource;
import com.forwardmeasure.decisionengine.jpa.service.RulesetVersionServiceImpl;
import com.forwardmeasure.jpa.tenancy.TenantScope;
import io.grpc.ServerInterceptor;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;

/**
 * Real {@link TenantScope}/{@code MultiTenantConnectionProvider} wiring now comes from {@code
 * forwardmeasure-jpa-micronaut}'s own {@code ForwardMeasureJpaFactory} (on the classpath via this
 * module's {@code forwardmeasure-jpa-micronaut} dependency) - the old hardcoded, always-{@code
 * TenantSchema.PUBLIC} {@code @Primary TenantScope} override that used to live in this class has
 * been deleted so that real, per-tenant-database factory bean is actually used.
 */
@Factory
public class DecisionEngineMicronautBinding {
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

  @io.micronaut.context.annotation.Bean(preDestroy = "close")
  @Singleton
  ValkeyFactWindowStore factWindow(
      @Value("${decision-engine.valkey.host}") String host,
      @Value("${decision-engine.valkey.port:6379}") int port,
      @Value("${decision-engine.valkey.password:}") String password) {
    return new ValkeyFactWindowStore(
        host, port, password.isBlank() ? null : password, new ObjectMapper());
  }

  @io.micronaut.context.annotation.Bean(preDestroy = "close")
  @Singleton
  TenantScopedRuleEvaluators evaluators(
      RulesetSource source,
      ValkeyFactWindowStore store,
      @Value("${decision-engine.cache.maximum-tenants:256}") int maximumTenants,
      @Value("${decision-engine.cache.rules-per-tenant:100}") int rulesPerTenant,
      @Value("${decision-engine.evaluation.maximum-rule-firings:10000}") int maxFirings) {
    return new TenantScopedRuleEvaluators(
        source, store, maximumTenants, rulesPerTenant, maxFirings);
  }

  @Singleton
  TenantExecution tenantExecution(
      TenantScope tenantScope,
      com.forwardmeasure.jpa.liquibase.TenantDatabaseResolver tenants,
      @Value("${decision-engine.tenant-resolution.issuer:}") String issuer,
      @Value("${decision-engine.tenant-resolution.organization-client-id:decisionengine}")
          String clientId,
      @Value("${decision-engine.authorization.client-secret:}") String secret) {
    if (secret.isBlank())
      throw new IllegalStateException("Decision authorization client secret is required");
    var authorization =
        com.forwardmeasure.authzen.client.AuthzenAuthorizationFactory.create(
            new ObjectMapper(),
            java.net.URI.create(issuer),
            clientId,
            secret,
            java.time.Duration.ofSeconds(5),
            java.time.Duration.ofSeconds(1),
            1000,
            "1");
    return new TenantExecution(tenantScope, tenants, authorization);
  }

  @Singleton
  TenantIdResolver tenantIdResolver(
      @Value("${decision-engine.tenant-resolution.mode:verified}") String mode,
      @Value("${decision-engine.tenant-resolution.jwks-uri:}") String jwksUri,
      @Value("${decision-engine.tenant-resolution.issuer:}") String issuer,
      @Value("${decision-engine.tenant-resolution.organization-client-id:decisionengine}")
          String clientId,
      @Value("${decision-engine.tenant-resolution.audience:decision-engine-api}") String audience) {
    if (!"verified".equals(mode)) {
      throw new IllegalStateException(
          "Production decision-engine requires verified tenant identity");
    }
    return new VerifiedJwtTenantResolver(jwksUri, issuer, clientId, audience);
  }

  /**
   * Any {@code @Singleton} bean of type {@link ServerInterceptor} is auto-registered globally by
   * {@code micronaut-grpc-server-runtime} (confirmed this session by decompiling {@code
   * GrpcServerBuilder.configureServerBuilder}) - no further wiring needed.
   */
  @io.micronaut.context.annotation.Context
  ServerInterceptor tenantInterceptor(
      TenantIdResolver resolver,
      javax.sql.DataSource controlPlane,
      ValkeyFactWindowStore store,
      TenantExecution execution) {
    // Probes run without a tenant or transaction. The Micronaut contextual datasource expects
    // an active connection scope; use its physical control-plane pool, as TenantRegistry does.
    var readinessDataSource =
        io.micronaut.data.connection.jdbc.advice.DelegatingDataSource.unwrapDataSource(
            controlPlane);
    return new TenantContextServerInterceptor(
        resolver,
        () ->
            TenantContextServerInterceptor.dependenciesHealthy(
                readinessDataSource, store::isHealthy));
  }

  @Singleton
  public static final class MicronautEvaluationService extends EvaluationServiceImpl {
    @Inject
    public MicronautEvaluationService(
        TenantScopedRuleEvaluators evaluators, TenantExecution tenantExecution) {
      super(evaluators, tenantExecution);
    }
  }

  @Singleton
  public static final class MicronautManagementService extends ManagementServiceImpl {
    @Inject
    public MicronautManagementService(
        RulesetVersionService service, TenantExecution tenantExecution) {
      super(service, tenantExecution);
    }
  }

  @Singleton
  public static final class MicronautAdminService extends AdminServiceImpl {
    @Inject
    public MicronautAdminService(
        TenantScopedRuleEvaluators evaluators, TenantExecution tenantExecution) {
      super(evaluators, tenantExecution);
    }
  }
}
