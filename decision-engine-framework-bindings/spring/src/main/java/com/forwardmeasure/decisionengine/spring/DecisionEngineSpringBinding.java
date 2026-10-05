/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.spring;

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
import com.forwardmeasure.jpa.tenancy.TenantScope;
import io.grpc.ServerInterceptor;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.service.GrpcService;

/**
 * Real {@link TenantScope}/{@code MultiTenantConnectionProvider} wiring comes from {@code
 * forwardmeasure-jpa-spring}'s own {@code ForwardMeasureJpaAutoConfiguration} (a Spring Boot
 * auto-configuration, activated automatically once this module's {@code forwardmeasure-jpa-spring}
 * dependency is on the classpath) - nothing in this class needs to produce a {@code TenantScope}
 * itself.
 */
@Configuration(proxyBeanMethods = false)
public class DecisionEngineSpringBinding {
  @Bean
  RulesetVersionRepository rulesetVersionRepository(EntityManager entityManager) {
    RulesetVersionRepository repository = new RulesetVersionRepository();
    repository.bindPersistenceContext(entityManager);
    return repository;
  }

  @Bean
  RulesetVersionService rulesetVersionService(RulesetVersionRepository repository) {
    return new RulesetVersionServiceImpl(repository, new DrlCompiler());
  }

  @Bean
  RulesetSource rulesetSource(RulesetVersionService service) {
    return new JpaRulesetSource(service);
  }

  @Bean
  ValkeyFactWindowStore factWindow(
      @Value("${decision-engine.valkey.host}") String host,
      @Value("${decision-engine.valkey.port:6379}") int port,
      @Value("${decision-engine.valkey.password:}") String password) {
    return new ValkeyFactWindowStore(
        host, port, password.isBlank() ? null : password, new ObjectMapper());
  }

  @Bean
  TenantScopedRuleEvaluators evaluators(RulesetSource source, ValkeyFactWindowStore store) {
    return new TenantScopedRuleEvaluators(source, store);
  }

  @Bean
  TenantExecution tenantExecution(TenantScope tenantScope) {
    return new TenantExecution(tenantScope);
  }

  /**
   * {@code trusting} (the default) is correct only for decision-engine's current internal-only
   * (ClusterIP) deployment, where every caller has already resolved real tenant identity upstream
   * through its own verified flow before calling decision-engine at all - see {@link
   * TrustingMetadataTenantResolver}'s own javadoc. {@code verified} is for a future standalone
   * deployment reachable outside that trust boundary - see {@link VerifiedJwtTenantResolver}. This
   * was a deliberate, already-made decision this session, not something to second-guess here.
   */
  @Bean
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

  @Bean
  @GlobalServerInterceptor
  ServerInterceptor tenantInterceptor(TenantIdResolver resolver) {
    return new TenantContextServerInterceptor(resolver);
  }

  @Bean
  @GrpcService
  EvaluationServiceImpl evaluationService(
      TenantScopedRuleEvaluators evaluators, TenantExecution tenantExecution) {
    return new EvaluationServiceImpl(evaluators, tenantExecution);
  }

  @Bean
  @GrpcService
  ManagementServiceImpl managementService(
      RulesetVersionService service, TenantExecution tenantExecution) {
    return new ManagementServiceImpl(service, tenantExecution);
  }

  @Bean
  @GrpcService
  AdminServiceImpl adminService(
      TenantScopedRuleEvaluators evaluators, TenantExecution tenantExecution) {
    return new AdminServiceImpl(evaluators, tenantExecution);
  }
}
