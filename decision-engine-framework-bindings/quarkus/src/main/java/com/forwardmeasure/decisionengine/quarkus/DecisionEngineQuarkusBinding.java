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
import com.forwardmeasure.decisionengine.contract.v1.CacheStatus;
import com.forwardmeasure.decisionengine.contract.v1.ClearCompiledRulesCacheRequest;
import com.forwardmeasure.decisionengine.contract.v1.CreateRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.DeleteRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.DeleteRulesetVersionResponse;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationResponse;
import com.forwardmeasure.decisionengine.contract.v1.GetActiveRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.GetCacheStatusRequest;
import com.forwardmeasure.decisionengine.contract.v1.GetStatisticsRequest;
import com.forwardmeasure.decisionengine.contract.v1.ListRulesetVersionsRequest;
import com.forwardmeasure.decisionengine.contract.v1.ListRulesetVersionsResponse;
import com.forwardmeasure.decisionengine.contract.v1.RuntimeStatistics;
import com.forwardmeasure.decisionengine.contract.v1.UnloadRulesetRequest;
import com.forwardmeasure.decisionengine.contract.v1.WarmRulesetRequest;
import com.forwardmeasure.decisionengine.core.DrlCompiler;
import com.forwardmeasure.decisionengine.domain.RulesetMode;
import com.forwardmeasure.decisionengine.domain.RulesetSource;
import com.forwardmeasure.decisionengine.domain.RulesetVersion;
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
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GlobalInterceptor;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
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

  /**
   * Managed bean ensures the service transaction completes inside TenantExecution, before a reply.
   */
  @ApplicationScoped
  @Transactional
  public static class TransactionalRulesets implements RulesetVersionService {
    private final RulesetVersionService delegate;

    @jakarta.inject.Inject
    public TransactionalRulesets(RulesetVersionRepository repository) {
      delegate = new RulesetVersionServiceImpl(repository, new DrlCompiler());
    }

    @Override
    public RulesetVersion create(
        String ruleset,
        String drl,
        RulesetMode mode,
        int maxWindowSize,
        int idleTimeoutSeconds,
        String createdBy,
        boolean activate) {
      return delegate.create(
          ruleset, drl, mode, maxWindowSize, idleTimeoutSeconds, createdBy, activate);
    }

    @Override
    public RulesetVersion getActive(String ruleset) {
      return delegate.getActive(ruleset);
    }

    @Override
    public RulesetVersion get(String ruleset, long version) {
      return delegate.get(ruleset, version);
    }

    @Override
    public List<RulesetVersion> list(String ruleset, String cursor, int limit) {
      return delegate.list(ruleset, cursor, limit);
    }

    @Override
    public RulesetVersion activate(String ruleset, long version) {
      return delegate.activate(ruleset, version);
    }

    @Override
    public boolean delete(String ruleset, long version) {
      return delegate.delete(ruleset, version);
    }
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
  TenantScopedRuleEvaluators evaluators(
      RulesetSource source,
      ValkeyFactWindowStore store,
      @ConfigProperty(name = "decision-engine.cache.maximum-tenants", defaultValue = "256")
          int maximumTenants,
      @ConfigProperty(name = "decision-engine.cache.rules-per-tenant", defaultValue = "100")
          int rulesPerTenant,
      @ConfigProperty(
              name = "decision-engine.evaluation.maximum-rule-firings",
              defaultValue = "10000")
          int maxFirings) {
    return new TenantScopedRuleEvaluators(
        source, store, maximumTenants, rulesPerTenant, maxFirings);
  }

  @Produces
  @ApplicationScoped
  @io.quarkus.runtime.Startup
  TenantExecution tenantExecution(
      TenantScope tenantScope,
      com.forwardmeasure.jpa.liquibase.TenantDatabaseResolver tenants,
      @ConfigProperty(name = "decision-engine.tenant-resolution.issuer", defaultValue = "")
          String issuer,
      @ConfigProperty(
              name = "decision-engine.tenant-resolution.organization-client-id",
              defaultValue = "decisionengine")
          String clientId,
      @ConfigProperty(name = "decision-engine.authorization.client-secret", defaultValue = "")
          String secret) {
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

  @Produces
  @ApplicationScoped
  @io.quarkus.runtime.Startup
  TenantIdResolver tenantIdResolver(
      @ConfigProperty(name = "decision-engine.tenant-resolution.mode", defaultValue = "verified")
          String mode,
      @ConfigProperty(name = "decision-engine.tenant-resolution.jwks-uri", defaultValue = "")
          String jwksUri,
      @ConfigProperty(name = "decision-engine.tenant-resolution.issuer", defaultValue = "")
          String issuer,
      @ConfigProperty(
              name = "decision-engine.tenant-resolution.organization-client-id",
              defaultValue = "decisionengine")
          String clientId,
      @ConfigProperty(
              name = "decision-engine.tenant-resolution.audience",
              defaultValue = "decision-engine-api")
          String audience) {
    if (!"verified".equals(mode)) {
      throw new IllegalStateException(
          "Production decision-engine requires verified tenant identity");
    }
    return new VerifiedJwtTenantResolver(jwksUri, issuer, clientId, audience);
  }

  void closeWindow(@jakarta.enterprise.inject.Disposes ValkeyFactWindowStore store) {
    store.close();
  }

  void closeEvaluators(@jakarta.enterprise.inject.Disposes TenantScopedRuleEvaluators evaluators) {
    evaluators.close();
  }

  /**
   * A {@code @Produces} factory method annotated {@code @GlobalInterceptor} does NOT reliably
   * register with Quarkus's gRPC server (live-verified via the container conformance test: the
   * interceptor was silently never invoked, no error, no log - a real Quarkus build-time bean-
   * discovery gap, not a Context-propagation issue). A concrete bean class annotated directly is
   * the pattern that actually works.
   */
  @Singleton
  @GlobalInterceptor
  public static class QuarkusTenantInterceptor extends TenantContextServerInterceptor {
    @jakarta.inject.Inject
    public QuarkusTenantInterceptor(
        TenantIdResolver resolver,
        javax.sql.DataSource controlPlane,
        ValkeyFactWindowStore store,
        TenantExecution execution) {
      super(resolver, () -> dependenciesHealthy(controlPlane, store::isHealthy));
    }
  }

  @GrpcService
  @Blocking
  @Singleton
  public static final class AdminService extends AdminServiceImpl {
    @jakarta.inject.Inject
    public AdminService(TenantScopedRuleEvaluators evaluators, TenantExecution tenantExecution) {
      super(evaluators, tenantExecution);
    }

    @Override
    @Blocking
    public void getStatistics(
        GetStatisticsRequest request, StreamObserver<RuntimeStatistics> observer) {
      super.getStatistics(request, observer);
    }

    @Override
    @Blocking
    public void getCacheStatus(
        GetCacheStatusRequest request, StreamObserver<CacheStatus> observer) {
      super.getCacheStatus(request, observer);
    }

    @Override
    @Blocking
    public void unloadRuleset(UnloadRulesetRequest request, StreamObserver<CacheStatus> observer) {
      super.unloadRuleset(request, observer);
    }

    @Override
    @Blocking
    public void clearCompiledRulesCache(
        ClearCompiledRulesCacheRequest request, StreamObserver<CacheStatus> observer) {
      super.clearCompiledRulesCache(request, observer);
    }

    @Override
    @Blocking
    public void warmRuleset(WarmRulesetRequest request, StreamObserver<CacheStatus> observer) {
      super.warmRuleset(request, observer);
    }
  }

  @GrpcService
  @Blocking
  @Singleton
  public static final class EvaluationService extends EvaluationServiceImpl {
    @jakarta.inject.Inject
    public EvaluationService(
        TenantScopedRuleEvaluators evaluators, TenantExecution tenantExecution) {
      super(evaluators, tenantExecution);
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
    public ManagementService(RulesetVersionService service, TenantExecution tenantExecution) {
      super(service, tenantExecution);
    }

    @Override
    @Blocking
    public void createRulesetVersion(
        CreateRulesetVersionRequest request,
        StreamObserver<com.forwardmeasure.decisionengine.contract.v1.RulesetVersion> observer) {
      super.createRulesetVersion(request, observer);
    }

    @Override
    @Blocking
    public void getActiveRulesetVersion(
        GetActiveRulesetVersionRequest request,
        StreamObserver<com.forwardmeasure.decisionengine.contract.v1.RulesetVersion> observer) {
      super.getActiveRulesetVersion(request, observer);
    }

    @Override
    @Blocking
    public void listRulesetVersions(
        ListRulesetVersionsRequest request, StreamObserver<ListRulesetVersionsResponse> observer) {
      super.listRulesetVersions(request, observer);
    }

    @Override
    @Blocking
    public void activateRulesetVersion(
        ActivateRulesetVersionRequest request,
        StreamObserver<com.forwardmeasure.decisionengine.contract.v1.RulesetVersion> observer) {
      super.activateRulesetVersion(request, observer);
    }

    @Override
    @Blocking
    public void deleteRulesetVersion(
        DeleteRulesetVersionRequest request,
        StreamObserver<DeleteRulesetVersionResponse> observer) {
      super.deleteRulesetVersion(request, observer);
    }
  }
}
