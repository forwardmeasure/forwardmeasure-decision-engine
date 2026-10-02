/*
 * Copyright 2026 Forward Measure
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.forwardmeasure.decisionengine.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.decisionengine.conformance.fixture.GoldenDataset;
import com.forwardmeasure.decisionengine.contract.v1.ClearCompiledRulesCacheRequest;
import com.forwardmeasure.decisionengine.contract.v1.CreateRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.DecisionEngineAdminServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.GetActiveRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.GetCacheStatusRequest;
import com.forwardmeasure.decisionengine.contract.v1.GetStatisticsRequest;
import com.forwardmeasure.decisionengine.contract.v1.RulesetManagementServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.RulesetMode;
import com.forwardmeasure.decisionengine.contract.v1.RulesetVersion;
import com.forwardmeasure.decisionengine.contract.v1.UnloadRulesetRequest;
import com.forwardmeasure.decisionengine.contract.v1.WarmRulesetRequest;
import com.forwardmeasure.decisionengine.grpc.tenancy.TrustingMetadataTenantResolver;
import com.forwardmeasure.jpa.tenancy.Did;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Full black-box conformance test with real databases and all deployable frameworks -
 * database-per-tenant now, not one shared database: provisions two real, distinct Postgres
 * databases via the real migration image (same {@code OpenWorkflowTenantMigrator}/{@code
 * RulesetMigrationsMain} that runs in production), then proves both real per-request tenant routing
 * (two tenants create a ruleset under the exact same name with different content and each only ever
 * sees its own) and, directly via raw JDBC bypassing gRPC entirely, that the two tenants' data
 * lives in genuinely separate physical Postgres databases - not just that the API happens to answer
 * correctly.
 */
class DecisionEngineContainerConformanceTest {
  private static final Logger LOG =
      LoggerFactory.getLogger(DecisionEngineContainerConformanceTest.class);
  private static final String POSTGRES_IMAGE = "postgres:17-alpine";
  private static final String VALKEY_IMAGE = "valkey/valkey:8.1";
  private static final String MIGRATION_IMAGE =
      "forwardmeasure/decision-engine-database-migration-service:1.1.0";
  private static final Map<String, String> SERVICE_IMAGES =
      Map.of(
          "quarkus", "forwardmeasure/decision-engine-quarkus:1.1.0",
          "spring", "forwardmeasure/decision-engine-spring:1.1.0",
          "micronaut", "forwardmeasure/decision-engine-micronaut:1.1.0");
  private static final String POSTGRES_SUPERUSER = "postgres";
  private static final String POSTGRES_SUPERUSER_PASSWORD = "postgres-password";
  private static final String RUNTIME_USERNAME = "decision_engine_runtime";
  private static final String RUNTIME_PASSWORD = "runtime-password";
  private static final String TENANT_DOMAIN = "conformance.test";
  private static final String TENANT_A_ALIAS = "tenant-a";
  private static final String TENANT_B_ALIAS = "tenant-b";
  private static final String SHARED_RULESET_NAME = "conformance/sharedName";

  @Test
  void allFrameworksRouteEachTenantToItsOwnPhysicalDatabase() throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("decision.engine.conformance.live"),
        "run with -Ddecision.engine.conformance.live=true after building local service images");
    try (Network network = Network.newNetwork();
        PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                // Deliberately the bootstrap/admin database, not "decision_engine" - real
                // per-tenant
                // databases are created by the migration Job itself (CREATE DATABASE, via
                // OpenWorkflowTenantMigrator), never pre-declared by this test.
                .withDatabaseName(POSTGRES_SUPERUSER)
                .withUsername(POSTGRES_SUPERUSER)
                .withPassword(POSTGRES_SUPERUSER_PASSWORD)
                .withNetwork(network)
                .withNetworkAliases("postgres");
        GenericContainer<?> valkey =
            new GenericContainer<>(VALKEY_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("valkey")
                .withExposedPorts(6379)
                .withCommand("--requirepass", "valkey-password")
                .waitingFor(Wait.forListeningPort());
        GenericContainer<?> migration = migration(network);
        GenericContainer<?> quarkus = service(network, "quarkus");
        GenericContainer<?> spring = service(network, "spring");
        GenericContainer<?> micronaut = service(network, "micronaut")) {
      postgres.start();
      valkey.start();
      migration.start();
      assertEquals(0, migration.getCurrentContainerInfo().getState().getExitCodeLong());
      quarkus.start();
      spring.start();
      micronaut.start();

      TenantId tenantA =
          TenantId.forDid(Did.parse("did:web:" + TENANT_A_ALIAS + "." + TENANT_DOMAIN));
      TenantId tenantB =
          TenantId.forDid(Did.parse("did:web:" + TENANT_B_ALIAS + "." + TENANT_DOMAIN));
      TenantDatabase databaseA = TenantDatabase.forAlias(TENANT_A_ALIAS);
      TenantDatabase databaseB = TenantDatabase.forAlias(TENANT_B_ALIAS);

      verifyBothTenantDatabasesExist(postgres, databaseA, databaseB);

      verifyFramework(
          "quarkus", quarkus.getMappedPort(9000), tenantA, tenantB, postgres, databaseA, databaseB);
      verifyFramework(
          "spring", spring.getMappedPort(9000), tenantA, tenantB, postgres, databaseA, databaseB);
      verifyFramework(
          "micronaut",
          micronaut.getMappedPort(9000),
          tenantA,
          tenantB,
          postgres,
          databaseA,
          databaseB);
    }
  }

  private static GenericContainer<?> migration(Network network) {
    return new GenericContainer<>(MIGRATION_IMAGE)
        .withNetwork(network)
        .withStartupCheckStrategy(
            new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))
        .withEnv(
            "DECISION_ENGINE_CONTROL_PLANE_DATABASE_URL",
            "jdbc:postgresql://postgres:5432/postgres")
        .withEnv("DECISION_ENGINE_ADMIN_DATABASE_USERNAME", POSTGRES_SUPERUSER)
        .withEnv("DECISION_ENGINE_ADMIN_DATABASE_PASSWORD", POSTGRES_SUPERUSER_PASSWORD)
        .withEnv("DECISION_ENGINE_RUNTIME_DATABASE_USERNAME", RUNTIME_USERNAME)
        .withEnv("DECISION_ENGINE_RUNTIME_DATABASE_PASSWORD", RUNTIME_PASSWORD)
        .withEnv(
            "DECISION_ENGINE_TENANTS", TENANT_A_ALIAS + ":Tenant A," + TENANT_B_ALIAS + ":Tenant B")
        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("migration"));
  }

  private static GenericContainer<?> service(Network network, String framework) {
    return new GenericContainer<>(SERVICE_IMAGES.get(framework))
        .withNetwork(network)
        .withNetworkAliases("decision-engine-" + framework)
        .withExposedPorts(9000)
        // Bootstrap-only datasource (build-time Hibernate dialect resolution) - never real tenant
        // data access, see application.{yml,properties}'s own comment on this in each deployment.
        .withEnv(
            "DECISION_ENGINE_CONTROL_PLANE_DATABASE_URL",
            "jdbc:postgresql://postgres:5432/postgres")
        .withEnv("DECISION_ENGINE_RUNTIME_DATABASE_USERNAME", RUNTIME_USERNAME)
        .withEnv("DECISION_ENGINE_RUNTIME_DATABASE_PASSWORD", RUNTIME_PASSWORD)
        // Real per-tenant routing (TenantDataSourceRegistry) - host/port broken out separately so
        // one JDBC URL per tenant database can be built.
        .withEnv("DECISION_ENGINE_TENANT_DATABASE_HOST", "postgres")
        .withEnv("DECISION_ENGINE_TENANT_DATABASE_PORT", "5432")
        .withEnv("DECISION_ENGINE_VALKEY_HOST", "valkey")
        .withEnv("DECISION_ENGINE_VALKEY_PORT", "6379")
        .withEnv("DECISION_ENGINE_VALKEY_PASSWORD", "valkey-password")
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofMinutes(2))
        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix(framework));
  }

  private static void verifyFramework(
      String framework,
      int port,
      TenantId tenantA,
      TenantId tenantB,
      PostgreSQLContainer<?> postgres,
      TenantDatabase databaseA,
      TenantDatabase databaseB)
      throws Exception {
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    try {
      // The chart's readiness/liveness probes are gRPC health checks that send no metadata: every
      // framework must serve grpc.health.v1.Health, and tenant resolution must not guard it.
      assertEquals(
          HealthCheckResponse.ServingStatus.SERVING,
          HealthGrpc.newBlockingStub(channel)
              .check(HealthCheckRequest.getDefaultInstance())
              .getStatus(),
          framework + " must serve grpc.health.v1.Health without tenant metadata");

      var managementA = withTenant(RulesetManagementServiceGrpc.newBlockingStub(channel), tenantA);
      var evaluationA = withTenant(EvaluationServiceGrpc.newBlockingStub(channel), tenantA);
      var adminA = withTenant(DecisionEngineAdminServiceGrpc.newBlockingStub(channel), tenantA);
      var managementB = withTenant(RulesetManagementServiceGrpc.newBlockingStub(channel), tenantB);
      var evaluationB = withTenant(EvaluationServiceGrpc.newBlockingStub(channel), tenantB);
      String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

      // Existing functional coverage - exercised under tenant A only (isolation itself is proven
      // separately below); still real end-to-end proof that tenant A's own database/schema/runtime
      // role actually works for every construct this project supports.
      verifyStatelessDatasets(managementA, evaluationA, adminA, suffix);
      verifyStatefulDataset(managementA, evaluationA, framework, suffix);
      assertStatus(
          Status.Code.INVALID_ARGUMENT,
          () -> evaluationA.evaluate(request("bad-name", Map.of("value", 1), "")));
      assertStatus(
          Status.Code.NOT_FOUND,
          () -> evaluationA.evaluate(request("missing/" + framework, Map.of("value", 1), "")));
      assertTrue(
          adminA.getStatistics(GetStatisticsRequest.getDefaultInstance()).getInvocationCount() > 0);

      // New: real cross-tenant isolation, same ruleset name, two tenants, over real gRPC against
      // real per-tenant Postgres databases. Ruleset name is qualified by framework since all
      // three frameworks share the same underlying Postgres containers in this one test run -
      // without that, the second and third frameworks' creates would land as new versions of the
      // SAME row the first framework already created, rather than each framework getting an
      // independent, isolated proof.
      String sharedRuleset = SHARED_RULESET_NAME + "/" + framework;
      verifyCrossTenantRulesetIsolation(
          managementA, evaluationA, managementB, evaluationB, framework, sharedRuleset);
      verifyPhysicalDatabaseIsolation(postgres, databaseA, databaseB, sharedRuleset);
    } finally {
      channel.shutdownNow();
    }
  }

  private static void verifyCrossTenantRulesetIsolation(
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub managementA,
      EvaluationServiceGrpc.EvaluationServiceBlockingStub evaluationA,
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub managementB,
      EvaluationServiceGrpc.EvaluationServiceBlockingStub evaluationB,
      String framework,
      String ruleset) {
    String drlA = sharedNameDrl("tenant-a-rule");
    String drlB = sharedNameDrl("tenant-b-rule");
    create(managementA, ruleset, drlA, RulesetMode.STATELESS, 0, 0, true);
    create(managementB, ruleset, drlB, RulesetMode.STATELESS, 0, 0, true);

    var responseA = evaluationA.evaluate(request(ruleset, Map.of("go", true), ""));
    var responseB = evaluationB.evaluate(request(ruleset, Map.of("go", true), ""));
    assertEquals(
        "tenant-a-rule",
        responseA.getResult().getFieldsOrThrow("outcome").getStringValue(),
        framework + ": tenant A must see only its own ruleset content");
    assertEquals(
        "tenant-b-rule",
        responseB.getResult().getFieldsOrThrow("outcome").getStringValue(),
        framework + ": tenant B must see only its own ruleset content");

    RulesetVersion activeA =
        managementA.getActiveRulesetVersion(
            GetActiveRulesetVersionRequest.newBuilder().setRuleset(ruleset).build());
    RulesetVersion activeB =
        managementB.getActiveRulesetVersion(
            GetActiveRulesetVersionRequest.newBuilder().setRuleset(ruleset).build());
    assertEquals(drlA, activeA.getDrl());
    assertEquals(drlB, activeB.getDrl());
    assertNotEquals(
        activeA.getDrl(), activeB.getDrl(), framework + ": tenants must never share stored DRL");
  }

  private static String sharedNameDrl(String outcome) {
    return "package conformance;\n"
        + "global java.util.Map result;\n"
        + "rule \"shared\"\n"
        + "when\n"
        + "  Map(this[\"go\"] == true)\n"
        + "then\n"
        + "  result.put(\"outcome\", \""
        + outcome
        + "\");\n"
        + "end\n";
  }

  private static void verifyBothTenantDatabasesExist(
      PostgreSQLContainer<?> postgres, TenantDatabase databaseA, TenantDatabase databaseB)
      throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), POSTGRES_SUPERUSER, POSTGRES_SUPERUSER_PASSWORD);
        Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "select datname from pg_database where datname in ('"
                    + databaseA.value()
                    + "', '"
                    + databaseB.value()
                    + "')")) {
      List<String> found = new ArrayList<>();
      while (rows.next()) {
        found.add(rows.getString(1));
      }
      assertEquals(
          2, found.size(), "both tenants must have their own real, distinct Postgres database");
    }
  }

  private static void verifyPhysicalDatabaseIsolation(
      PostgreSQLContainer<?> postgres,
      TenantDatabase databaseA,
      TenantDatabase databaseB,
      String ruleset)
      throws Exception {
    String urlPrefix =
        "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/";
    assertOnlyRowIn(urlPrefix + databaseA.value(), ruleset, "tenant-a-rule");
    assertOnlyRowIn(urlPrefix + databaseB.value(), ruleset, "tenant-b-rule");
  }

  private static void assertOnlyRowIn(
      String tenantJdbcUrl, String ruleset, String expectedOutcomeFragment) throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                tenantJdbcUrl, POSTGRES_SUPERUSER, POSTGRES_SUPERUSER_PASSWORD);
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "select drl from decision_intelligence.ruleset_version where ruleset = '"
                    + ruleset
                    + "'")) {
      assertTrue(rows.next(), "expected a ruleset_version row directly inside " + tenantJdbcUrl);
      assertTrue(
          rows.getString(1).contains(expectedOutcomeFragment),
          "row in " + tenantJdbcUrl + " must contain this tenant's own DRL, not the other's");
      assertFalse(
          rows.next(), "each tenant's own database must contain exactly one row for this ruleset");
    }
  }

  private static <S extends AbstractStub<S>> S withTenant(S stub, TenantId tenantId) {
    Metadata headers = new Metadata();
    headers.put(TrustingMetadataTenantResolver.TENANT_ID_HEADER, tenantId.value().toString());
    return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  private static void verifyStatelessDatasets(
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub management,
      EvaluationServiceGrpc.EvaluationServiceBlockingStub evaluation,
      DecisionEngineAdminServiceGrpc.DecisionEngineAdminServiceBlockingStub admin,
      String suffix) {
    for (String fixture : List.of("payments-risk", "customer-segmentation")) {
      GoldenDataset dataset = GoldenDataset.load(fixture);
      String ruleset = dataset.ruleset() + suffix;
      var active =
          create(
              management,
              ruleset,
              dataset.drl(),
              RulesetMode.STATELESS,
              dataset.maxWindowSize(),
              dataset.idleTimeoutSeconds(),
              true);
      var pinned =
          create(
              management,
              ruleset,
              dataset.drl(),
              RulesetMode.STATELESS,
              dataset.maxWindowSize(),
              dataset.idleTimeoutSeconds(),
              false);
      for (GoldenDataset.GoldenCase testCase : dataset.cases()) {
        var response = evaluation.evaluate(request(ruleset, testCase.input(), ""));
        assertEquals(
            testCase.outcome(), response.getResult().getFieldsOrThrow("outcome").getStringValue());
        assertEquals(testCase.firedRules(), response.getFiredRulesList());
        assertEquals(testCase.factsConsidered(), response.getFactsConsidered());
      }
      var pinnedResponse =
          evaluation.evaluate(
              request(ruleset, dataset.cases().get(0).input(), "", pinned.getVersion()));
      assertEquals(pinned.getVersion(), pinnedResponse.getRulesetVersion());
      var warmed =
          admin.warmRuleset(
              WarmRulesetRequest.newBuilder()
                  .setRuleset(ruleset)
                  .setVersion(active.getVersion())
                  .build());
      assertTrue(
          warmed.getEntriesList().stream().anyMatch(entry -> entry.getRuleset().equals(ruleset)));
      assertTrue(admin.getCacheStatus(GetCacheStatusRequest.getDefaultInstance()).getSize() > 0);
      admin.unloadRuleset(
          UnloadRulesetRequest.newBuilder()
              .setRuleset(ruleset)
              .setVersion(active.getVersion())
              .build());
      admin.clearCompiledRulesCache(ClearCompiledRulesCacheRequest.getDefaultInstance());
    }
  }

  private static void verifyStatefulDataset(
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub management,
      EvaluationServiceGrpc.EvaluationServiceBlockingStub evaluation,
      String framework,
      String suffix) {
    GoldenDataset dataset = GoldenDataset.load("transaction-velocity");
    String ruleset = dataset.ruleset() + suffix;
    create(
        management,
        ruleset,
        dataset.drl(),
        RulesetMode.STATEFUL,
        dataset.maxWindowSize(),
        dataset.idleTimeoutSeconds(),
        true);
    String session = framework + "-" + suffix;
    for (GoldenDataset.GoldenCase testCase : dataset.cases()) {
      if (testCase.expectedStatus() != null) {
        assertStatus(
            Status.Code.valueOf(testCase.expectedStatus()),
            () -> evaluation.evaluate(request(ruleset, testCase.input(), session)),
            framework + " stateful case " + testCase.name());
      } else {
        var response = evaluation.evaluate(request(ruleset, testCase.input(), session));
        assertEquals(
            testCase.outcome(), response.getResult().getFieldsOrThrow("outcome").getStringValue());
        assertEquals(testCase.firedRules(), response.getFiredRulesList());
        assertEquals(testCase.factsConsidered(), response.getFactsConsidered());
      }
    }
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () ->
            evaluation.evaluate(
                request(ruleset, dataset.cases().get(0).input(), session + "-isolated")));
  }

  private static RulesetVersion create(
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub management,
      String ruleset,
      String drl,
      RulesetMode mode,
      int maxWindowSize,
      int idleTimeoutSeconds,
      boolean activate) {
    var response =
        management.createRulesetVersion(
            CreateRulesetVersionRequest.newBuilder()
                .setRuleset(ruleset)
                .setDrl(drl)
                .setMode(mode)
                .setMaxWindowSize(maxWindowSize)
                .setIdleTimeoutSeconds(idleTimeoutSeconds)
                .setCreatedBy("container-conformance")
                .setActivate(activate)
                .build());
    if (activate) assertTrue(response.getActive());
    return response;
  }

  private static EvaluationRequest request(
      String ruleset, Map<String, Object> facts, String session) {
    return request(ruleset, facts, session, null);
  }

  private static EvaluationRequest request(
      String ruleset, Map<String, Object> facts, String session, Long version) {
    Struct input =
        Struct.newBuilder()
            .putAllFields(
                facts.entrySet().stream()
                    .collect(
                        java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, entry -> value(entry.getValue()))))
            .build();
    var builder =
        EvaluationRequest.newBuilder().setRuleset(ruleset).setInput(input).setSessionKey(session);
    if (version != null) builder.setRulesetVersion(version);
    return builder.build();
  }

  private static Value value(Object value) {
    if (value instanceof Boolean booleanValue)
      return Value.newBuilder().setBoolValue(booleanValue).build();
    if (value instanceof Number number)
      return Value.newBuilder().setNumberValue(number.doubleValue()).build();
    return Value.newBuilder().setStringValue(String.valueOf(value)).build();
  }

  private static void assertStatus(Status.Code expected, Runnable invocation) {
    assertStatus(expected, invocation, "request");
  }

  private static void assertStatus(Status.Code expected, Runnable invocation, String context) {
    try {
      invocation.run();
      throw new AssertionError(context + ": expected " + expected);
    } catch (StatusRuntimeException failure) {
      assertEquals(expected, failure.getStatus().getCode(), context + ": " + failure.getStatus());
    }
  }
}
