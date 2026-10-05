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
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GlobalInterceptor;
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
  TenantScopedRuleEvaluators evaluators(RulesetSource source, ValkeyFactWindowStore store) {
    return new TenantScopedRuleEvaluators(source, store);
  }

  @Produces
  @ApplicationScoped
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
  @Produces
  @ApplicationScoped
  TenantIdResolver tenantIdResolver(
      @ConfigProperty(name = "decision-engine.tenant-resolution.mode", defaultValue = "trusting")
          String mode,
      @ConfigProperty(name = "decision-engine.tenant-resolution.jwks-uri") Optional<String> jwksUri,
      @ConfigProperty(name = "decision-engine.tenant-resolution.issuer") Optional<String> issuer,
      @ConfigProperty(name = "decision-engine.tenant-resolution.organization-client-id")
          Optional<String> organizationClientId) {
    if ("verified".equalsIgnoreCase(mode)) {
      return new VerifiedJwtTenantResolver(
          requireForVerifiedMode(jwksUri, "decision-engine.tenant-resolution.jwks-uri"),
          requireForVerifiedMode(issuer, "decision-engine.tenant-resolution.issuer"),
          requireForVerifiedMode(
              organizationClientId, "decision-engine.tenant-resolution.organization-client-id"));
    }
    return new TrustingMetadataTenantResolver();
  }

  private static String requireForVerifiedMode(Optional<String> value, String propertyName) {
    return value
        .filter(v -> !v.isBlank())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    propertyName
                        + " is required when decision-engine.tenant-resolution.mode=verified"));
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
    public QuarkusTenantInterceptor(TenantIdResolver resolver) {
      super(resolver);
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
