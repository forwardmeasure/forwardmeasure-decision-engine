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
  TenantScopedRuleEvaluators evaluators(
      RulesetSource source,
      ValkeyFactWindowStore store,
      @Value("${decision-engine.cache.maximum-tenants:256}") int maximumTenants,
      @Value("${decision-engine.cache.rules-per-tenant:100}") int rulesPerTenant,
      @Value("${decision-engine.evaluation.maximum-rule-firings:10000}") int maxFirings) {
    return new TenantScopedRuleEvaluators(
        source, store, maximumTenants, rulesPerTenant, maxFirings);
  }

  @Bean
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

  @Bean
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

  @Bean
  @GlobalServerInterceptor
  ServerInterceptor tenantInterceptor(
      TenantIdResolver resolver,
      javax.sql.DataSource controlPlane,
      ValkeyFactWindowStore store,
      TenantExecution execution) {
    return new TenantContextServerInterceptor(
        resolver,
        () -> TenantContextServerInterceptor.dependenciesHealthy(controlPlane, store::isHealthy));
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
