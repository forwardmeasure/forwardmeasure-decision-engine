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
import com.forwardmeasure.decisionengine.grpc.tenancy.TrustingMetadataTenantResolver;
import com.forwardmeasure.decisionengine.grpc.tenancy.VerifiedJwtTenantResolver;
import com.forwardmeasure.decisionengine.jpa.application.RulesetVersionService;
import com.forwardmeasure.decisionengine.jpa.repository.RulesetVersionRepository;
import com.forwardmeasure.decisionengine.jpa.service.JpaRulesetSource;
import com.forwardmeasure.decisionengine.jpa.service.RulesetVersionServiceImpl;
import com.forwardmeasure.jpa.liquibase.TenantDatabaseResolver;
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

  @Singleton
  ValkeyFactWindowStore factWindow(
      @Value("${decision-engine.valkey.host}") String host,
      @Value("${decision-engine.valkey.port:6379}") int port,
      @Value("${decision-engine.valkey.password:}") String password) {
    return new ValkeyFactWindowStore(
        host, port, password.isBlank() ? null : password, new ObjectMapper());
  }

  @Singleton
  TenantScopedRuleEvaluators evaluators(RulesetSource source, ValkeyFactWindowStore store) {
    return new TenantScopedRuleEvaluators(source, store);
  }

  @Singleton
  TenantExecution tenantExecution(TenantScope tenantScope, TenantDatabaseResolver databases) {
    return new TenantExecution(tenantScope, databases);
  }

  /**
   * {@code trusting} (the default) is correct only for decision-engine's current internal-only
   * (ClusterIP) deployment, where every caller has already resolved real tenant identity upstream
   * through its own verified flow before calling decision-engine at all - see {@link
   * TrustingMetadataTenantResolver}'s own javadoc. {@code verified} is for a future standalone
   * deployment reachable outside that trust boundary - see {@link VerifiedJwtTenantResolver}. This
   * was a deliberate, already-made decision this session, not something to second-guess here.
   */
  @Singleton
  TenantIdResolver tenantIdResolver(
      @Value("${decision-engine.tenant-resolution.mode:trusting}") String mode,
      @Value("${decision-engine.tenant-resolution.jwks-uri:}") String jwksUri,
      @Value("${decision-engine.tenant-resolution.issuer:}") String issuer,
      @Value("${decision-engine.tenant-resolution.organization-client-id:}")
          String organizationClientId) {
    if ("verified".equalsIgnoreCase(mode)) {
      return new VerifiedJwtTenantResolver(
          requireForVerifiedMode(jwksUri, "decision-engine.tenant-resolution.jwks-uri"),
          requireForVerifiedMode(issuer, "decision-engine.tenant-resolution.issuer"),
          requireForVerifiedMode(
              organizationClientId, "decision-engine.tenant-resolution.organization-client-id"));
    }
    return new TrustingMetadataTenantResolver();
  }

  private static String requireForVerifiedMode(String value, String propertyName) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          propertyName + " is required when decision-engine.tenant-resolution.mode=verified");
    }
    return value;
  }

  /**
   * Any {@code @Singleton} bean of type {@link ServerInterceptor} is auto-registered globally by
   * {@code micronaut-grpc-server-runtime} (confirmed this session by decompiling {@code
   * GrpcServerBuilder.configureServerBuilder}) - no further wiring needed.
   */
  @Singleton
  ServerInterceptor tenantInterceptor(TenantIdResolver resolver) {
    return new TenantContextServerInterceptor(resolver);
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
