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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.authzen.testkit.AuthzenKeycloakFixture;
import com.forwardmeasure.decisionengine.conformance.fixture.GoldenDataset;
import com.forwardmeasure.decisionengine.contract.v1.ActivateRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.ClearCompiledRulesCacheRequest;
import com.forwardmeasure.decisionengine.contract.v1.CreateRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.DecisionEngineAdminServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.DeleteRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.GetActiveRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.GetCacheStatusRequest;
import com.forwardmeasure.decisionengine.contract.v1.GetStatisticsRequest;
import com.forwardmeasure.decisionengine.contract.v1.ListRulesetVersionsRequest;
import com.forwardmeasure.decisionengine.contract.v1.RulesetManagementServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.RulesetMode;
import com.forwardmeasure.decisionengine.contract.v1.RulesetVersion;
import com.forwardmeasure.decisionengine.contract.v1.UnloadRulesetRequest;
import com.forwardmeasure.decisionengine.contract.v1.WarmRulesetRequest;
import com.forwardmeasure.jpa.tenancy.Did;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import com.forwardmeasure.testcontainers.valkey.ValkeyTestContainer;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
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
  private static final String AUDIENCE = "decision-engine-api";
  static final String TEST_CLIENT_SECRET = "conformance-client-secret";
  private static final String POSTGRES_SUPERUSER = "postgres";
  private static final String POSTGRES_SUPERUSER_PASSWORD = "postgres-password";
  private static final String RUNTIME_USERNAME = "decision_engine_runtime";
  private static final String RUNTIME_PASSWORD = "runtime-password";
  private static final String TENANT_DOMAIN = "conformance.test";
  private static final String TENANT_A_ALIAS = "tenant-a";
  private static final String TENANT_B_ALIAS = "tenant-b";
  private static final String SHARED_RULESET_NAME = "conformance/sharedName";

  @ParameterizedTest
  @ValueSource(strings = {"quarkus", "spring", "micronaut"})
  @Timeout(300)
  void authenticatedFrameworkRoutesEachTenantToItsOwnPhysicalDatabase(String framework)
      throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("decision.engine.conformance.live"),
        "enable full-suite and build the declared local service images");
    try (Network network = Network.newNetwork();
        AuthzenKeycloakFixture identity = AuthzenKeycloakFixture.start(network, "keycloak");
        PostgreSqlTestContainer postgres =
            new PostgreSqlTestContainer(
                new PostgreSqlContainerConfiguration(
                        PostgreSqlContainerConfiguration.DEFAULT_IMAGE,
                        POSTGRES_SUPERUSER,
                        POSTGRES_SUPERUSER,
                        POSTGRES_SUPERUSER_PASSWORD,
                        Optional.empty(),
                        List.of(),
                        PostgreSqlContainerConfiguration.DEFAULT_MEMORY_BYTES,
                        PostgreSqlContainerConfiguration.DEFAULT_MEMORY_SWAP_BYTES)
                    .withNetwork(network.getId(), List.of("postgres")));
        ValkeyTestContainer valkey = new ValkeyTestContainer(network, "valkey", "valkey-password");
        GenericContainer<?> registryMigration = registryMigration(network);
        GenericContainer<?> migration = migration(network);
        GenericContainer<?> service = service(network, framework, identity)) {
      String organizationA =
          identity.provisionTenant(
              TENANT_A_ALIAS,
              Did.parse("did:web:" + TENANT_A_ALIAS + "." + TENANT_DOMAIN),
              "decision-admin");
      String organizationB =
          identity.provisionTenant(
              TENANT_B_ALIAS,
              Did.parse("did:web:" + TENANT_B_ALIAS + "." + TENANT_DOMAIN),
              "decision-admin");
      identity.grantOrganizationClientRole(organizationA, "decision-evaluator");
      identity.grantResourceAuthorization(
          organizationA,
          "decision-engine",
          "rulesets",
          "evaluate-decisions",
          "decision-evaluator",
          Set.of("decision:evaluate"));
      for (String organization : List.of(organizationA, organizationB)) {
        identity.grantResourceAuthorization(
            organization,
            "decision-engine",
            "rulesets",
            "manage-decisions",
            "decision-admin",
            Set.of("decision:evaluate", "decision:manage", "decision:admin"));
      }
      postgres.start();
      valkey.start();
      // The owning FOWF migration job establishes tenant identity/registry entries. FDE's
      // migration job adds its real schema; the test never seeds business rows with SQL.
      registryMigration.start();
      assertEquals(0, registryMigration.getCurrentContainerInfo().getState().getExitCodeLong());
      migration.start();
      assertEquals(0, migration.getCurrentContainerInfo().getState().getExitCodeLong());
      configureCoverage(service);
      service.start();
      // Mint short-lived credentials after image startup so infrastructure startup time does
      // not consume the authenticated scenario's token lifetime.
      String tokenA =
          token(identity, "decision-tenant-a", organizationA, "decision-admin", AUDIENCE);
      String tokenB =
          token(identity, "decision-tenant-b", organizationB, "decision-admin", AUDIENCE);
      String deniedToken = token(identity, "decision-denied", organizationA, "ungranted", AUDIENCE);
      String wrongAudience =
          token(
              identity,
              "decision-wrong-audience",
              organizationA,
              "decision-admin",
              "another-service");
      String evaluatorToken =
          token(identity, "decision-evaluator", organizationA, "decision-evaluator", AUDIENCE);
      LOG.info(
          "Conformance framework={} imageId={}",
          framework,
          service.getCurrentContainerInfo().getImageId());
      TenantId tenantA =
          TenantId.forDid(Did.parse("did:web:" + TENANT_A_ALIAS + "." + TENANT_DOMAIN));
      TenantId tenantB =
          TenantId.forDid(Did.parse("did:web:" + TENANT_B_ALIAS + "." + TENANT_DOMAIN));
      TenantDatabase databaseA = TenantDatabase.forAlias(TENANT_A_ALIAS);
      TenantDatabase databaseB = TenantDatabase.forAlias(TENANT_B_ALIAS);
      verifyBothTenantDatabasesExist(postgres, databaseA, databaseB);
      verifyFramework(
          framework,
          service.getMappedPort(9000),
          tenantA,
          tenantB,
          tokenA,
          tokenB,
          deniedToken,
          wrongAudience,
          evaluatorToken,
          postgres,
          databaseA,
          databaseB);
      dumpCoverage(service);
    }
  }

  private static void configureCoverage(GenericContainer<?> service) {
    String agent = System.getProperty("decision.engine.conformance.jacoco-agent", "");
    if (agent.isBlank()) return;
    var path = java.nio.file.Path.of(agent);
    if (!java.nio.file.Files.isRegularFile(path)) {
      throw new IllegalArgumentException("Coverage agent is not a regular file: " + path);
    }
    service.withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(path), "/tmp/acceptance-jacoco-agent.jar");
    service.addExposedPort(6300);
    service.withEnv("JAVA_TOOL_OPTIONS", service.getEnvMap().get("JAVA_TOOL_OPTIONS")
        + " -javaagent:/tmp/acceptance-jacoco-agent.jar=output=tcpserver,address=0.0.0.0,port=6300,includes=com.forwardmeasure.decisionengine.*");
  }

  private static void dumpCoverage(GenericContainer<?> service) throws java.io.IOException {
    if (System.getProperty("decision.engine.conformance.jacoco-agent", "").isBlank()) return;
    var dump = new org.jacoco.core.tools.ExecDumpClient();
    dump.setDump(true);
    dump.setReset(false);
    var execution = dump.dump(service.getHost(), service.getMappedPort(6300));
    assertFalse(execution.getExecutionDataStore().getContents().isEmpty(), "Service JVM must supply actual coverage data");
    var destination = java.nio.file.Path.of(System.getProperty("decision.engine.conformance.jacoco-output", "target/jacoco.exec"));
    java.nio.file.Files.createDirectories(destination.toAbsolutePath().getParent());
    execution.save(destination.toFile(), true);
  }

  static String token(
      AuthzenKeycloakFixture identity,
      String clientId,
      String organization,
      String role,
      String audience) {
    identity.createServiceAccountClient(clientId, TEST_CLIENT_SECRET);
    identity.addServiceAccountToOrganization(organization, clientId, role);
    identity.grantTokenAudience(clientId, audience);
    return identity.clientCredentialsToken(clientId, TEST_CLIENT_SECRET);
  }

  static String image(String component) {
    String image = System.getProperty("decision.engine.image." + component);
    if (image == null || image.isBlank())
      throw new IllegalStateException("Missing current image property for " + component);
    return image;
  }

  static GenericContainer<?> registryMigration(Network network) {
    return new GenericContainer<>(image("registry-migration"))
        .withImagePullPolicy(imageName -> false)
        .withCreateContainerCmdModifier(
            command -> command.getHostConfig().withMemory(2L * 1024 * 1024 * 1024))
        .withNetwork(network)
        .withStartupCheckStrategy(
            new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))
        .withEnv(
            "OPENWORKFLOW_CONTROL_PLANE_DATABASE_URL", "jdbc:postgresql://postgres:5432/postgres")
        .withEnv("OPENWORKFLOW_ADMIN_DATABASE_USERNAME", POSTGRES_SUPERUSER)
        .withEnv("OPENWORKFLOW_ADMIN_DATABASE_PASSWORD", POSTGRES_SUPERUSER_PASSWORD)
        .withEnv("OPENWORKFLOW_RUNTIME_DATABASE_USERNAME", "openworkflow_runtime")
        .withEnv("OPENWORKFLOW_RUNTIME_DATABASE_PASSWORD", "workflow-test-password")
        .withEnv("OPENWORKFLOW_TENANT_DOMAIN", TENANT_DOMAIN)
        .withEnv(
            "OPENWORKFLOW_TENANTS", TENANT_A_ALIAS + ":Tenant A," + TENANT_B_ALIAS + ":Tenant B")
        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("registry-migration"));
  }

  static GenericContainer<?> migration(Network network) {
    return new GenericContainer<>(image("migration"))
        .withImagePullPolicy(imageName -> false)
        .withCreateContainerCmdModifier(
            command -> command.getHostConfig().withMemory(2L * 1024 * 1024 * 1024))
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

  static GenericContainer<?> service(
      Network network, String framework, AuthzenKeycloakFixture identity) {
    return new GenericContainer<>(image(framework))
        .withImagePullPolicy(imageName -> false)
        .withEnv(
            "JAVA_TOOL_OPTIONS",
            System.getProperty("forwardmeasure.acceptance.java-tool-options", "-Xmx1g"))
        .withCreateContainerCmdModifier(
            command -> command.getHostConfig().withMemory(2L * 1024 * 1024 * 1024))
        .withNetwork(network)
        .withNetworkAliases("decision-engine-" + framework)
        .withEnv(
            "DECISION_ENGINE_TENANT_RESOLUTION_JWKS_URI",
            identity.networkIssuer() + "/protocol/openid-connect/certs")
        .withEnv("DECISION_ENGINE_TENANT_RESOLUTION_ISSUER", identity.networkIssuer().toString())
        .withEnv(
            "DECISION_ENGINE_TENANT_RESOLUTION_ORGANIZATION_CLIENT_ID",
            AuthzenKeycloakFixture.AUTHZEN_CLIENT_ID)
        .withEnv("DECISION_ENGINE_TENANT_RESOLUTION_AUDIENCE", AUDIENCE)
        .withEnv("DECISION_ENGINE_CLIENT_SECRET", AuthzenKeycloakFixture.AUTHZEN_CLIENT_SECRET)
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
      String tokenA,
      String tokenB,
      String deniedToken,
      String wrongAudience,
      String evaluatorToken,
      PostgreSqlTestContainer postgres,
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
              .withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS)
              .check(HealthCheckRequest.newBuilder().setService("liveness").build())
              .getStatus());
      // An open TCP port is not dependency readiness. Poll the public probe, just as kubelet
      // does, with a deadline on each RPC and a bounded overall startup window.
      await()
          .alias(framework + " anonymous gRPC readiness")
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(
              () ->
                  assertEquals(
                      HealthCheckResponse.ServingStatus.SERVING,
                      HealthGrpc.newBlockingStub(channel)
                          .withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS)
                          .check(HealthCheckRequest.newBuilder().setService("readiness").build())
                          .getStatus()));
      assertEquals(
          HealthCheckResponse.ServingStatus.SERVING,
          HealthGrpc.newBlockingStub(channel)
              .withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS)
              .check(HealthCheckRequest.getDefaultInstance())
              .getStatus());

      var anonymous = EvaluationServiceGrpc.newBlockingStub(channel);
      assertStatus(
          Status.Code.UNAUTHENTICATED,
          () -> anonymous.evaluate(request("missing/ruleset", Map.of(), "")));
      Metadata unsigned = new Metadata();
      unsigned.put(
          Metadata.Key.of("tenant-id", Metadata.ASCII_STRING_MARSHALLER),
          tenantA.value().toString());
      assertStatus(
          Status.Code.UNAUTHENTICATED,
          () ->
              anonymous
                  .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(unsigned))
                  .evaluate(request("missing/ruleset", Map.of(), "")));
      assertStatus(
          Status.Code.UNAUTHENTICATED,
          () ->
              withBearer(anonymous, wrongAudience)
                  .evaluate(request("missing/ruleset", Map.of(), "")));
      assertStatus(
          Status.Code.PERMISSION_DENIED,
          () ->
              withBearer(anonymous, deniedToken)
                  .evaluate(request("missing/ruleset", Map.of(), "")));
      Metadata conflicting = new Metadata();
      conflicting.put(
          Metadata.Key.of("tenant-id", Metadata.ASCII_STRING_MARSHALLER),
          tenantB.value().toString());
      assertStatus(
          Status.Code.UNAUTHENTICATED,
          () ->
              withBearer(anonymous, tokenA)
                  .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(conflicting))
                  .evaluate(request("missing/ruleset", Map.of(), "")));

      var managementA = withBearer(RulesetManagementServiceGrpc.newBlockingStub(channel), tokenA);
      var evaluationA = withBearer(EvaluationServiceGrpc.newBlockingStub(channel), tokenA);
      var adminA = withBearer(DecisionEngineAdminServiceGrpc.newBlockingStub(channel), tokenA);
      var managementB = withBearer(RulesetManagementServiceGrpc.newBlockingStub(channel), tokenB);
      var evaluationB = withBearer(EvaluationServiceGrpc.newBlockingStub(channel), tokenB);
      String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

      // Existing functional coverage - exercised under tenant A only (isolation itself is proven
      // separately below); still real end-to-end proof that tenant A's own database/schema/runtime
      // role actually works for every construct this project supports.
      verifyStatelessDatasets(managementA, evaluationA, adminA, suffix);
      verifyVersionLifecycle(managementA, evaluationA, suffix);
      verifyStatefulDataset(managementA, evaluationA, framework, suffix);
      assertStatus(
          Status.Code.INVALID_ARGUMENT,
          () -> evaluationA.evaluate(request("bad-name", Map.of("value", 1), "")));
      assertStatus(
          Status.Code.NOT_FOUND,
          () -> evaluationA.evaluate(request("missing/" + framework, Map.of("value", 1), "")));
      assertTrue(
          adminA.getStatistics(GetStatisticsRequest.getDefaultInstance()).getInvocationCount() > 0);

      // Same ruleset name, distinct tenants and physical databases. Each framework case owns
      // fresh infrastructure; the name also identifies its framework in failure diagnostics.
      String sharedRuleset = SHARED_RULESET_NAME + "/" + framework;
      verifyCrossTenantRulesetIsolation(
          managementA, evaluationA, managementB, evaluationB, framework, sharedRuleset);
      verifyEvaluatorCannotManageOrAdminister(
          channel, evaluatorToken, managementA, adminA, sharedRuleset);
      verifyCrossTenantStatefulIsolation(
          managementA, evaluationA, managementB, evaluationB, sharedRuleset + "/stateful");
      verifyPhysicalDatabaseIsolation(postgres, databaseA, databaseB, sharedRuleset);
    } finally {
      channel.shutdownNow();
    }
  }

  private static void verifyEvaluatorCannotManageOrAdminister(
      ManagedChannel channel,
      String evaluatorToken,
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub management,
      DecisionEngineAdminServiceGrpc.DecisionEngineAdminServiceBlockingStub admin,
      String ruleset) {
    var evaluator = withBearer(EvaluationServiceGrpc.newBlockingStub(channel), evaluatorToken);
    assertEquals(
        "tenant-a-rule",
        evaluator
            .evaluate(request(ruleset, Map.of("go", true), ""))
            .getResult()
            .getFieldsOrThrow("outcome")
            .getStringValue());
    var activeRequest = GetActiveRulesetVersionRequest.newBuilder().setRuleset(ruleset).build();
    var activeBefore = management.getActiveRulesetVersion(activeRequest);
    var restrictedManagement =
        withBearer(RulesetManagementServiceGrpc.newBlockingStub(channel), evaluatorToken);
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        () ->
            create(
                restrictedManagement,
                ruleset,
                sharedNameDrl("unauthorized-replacement"),
                RulesetMode.STATELESS,
                0,
                0,
                true));
    assertEquals(activeBefore, management.getActiveRulesetVersion(activeRequest));
    long cacheSize = admin.getCacheStatus(GetCacheStatusRequest.getDefaultInstance()).getSize();
    assertTrue(cacheSize > 0);
    var restrictedAdmin =
        withBearer(DecisionEngineAdminServiceGrpc.newBlockingStub(channel), evaluatorToken);
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        () ->
            restrictedAdmin.clearCompiledRulesCache(
                ClearCompiledRulesCacheRequest.getDefaultInstance()));
    assertEquals(
        cacheSize, admin.getCacheStatus(GetCacheStatusRequest.getDefaultInstance()).getSize());
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

  static String sharedNameDrl(String outcome) {
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
      PostgreSqlTestContainer postgres, TenantDatabase databaseA, TenantDatabase databaseB)
      throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                postgres.hostJdbcUrl(), POSTGRES_SUPERUSER, POSTGRES_SUPERUSER_PASSWORD);
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
      PostgreSqlTestContainer postgres,
      TenantDatabase databaseA,
      TenantDatabase databaseB,
      String ruleset)
      throws Exception {
    String urlPrefix = "jdbc:postgresql://" + postgres.host() + ":" + postgres.mappedPort() + "/";
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

  static <S extends AbstractStub<S>> S withBearer(S stub, String token) {
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
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

  private static void verifyVersionLifecycle(
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub management,
      EvaluationServiceGrpc.EvaluationServiceBlockingStub evaluation,
      String suffix) throws Exception {
    String ruleset = "conformance/lifecycle" + suffix;
    var first = create(management, ruleset, sharedNameDrl("first"), RulesetMode.STATELESS, 0, 0, true);
    var second = create(management, ruleset, sharedNameDrl("second"), RulesetMode.STATELESS, 0, 0, false);
    assertEquals(1, first.getVersion());
    assertEquals(2, second.getVersion());
    assertFalse(second.getActive());
    var input = request(ruleset, Map.of("go", true), "");
    assertEquals("first", evaluation.evaluate(input).getResult().getFieldsOrThrow("outcome").getStringValue());
    assertEquals("second", evaluation.evaluate(input.toBuilder().setRulesetVersion(2).build()).getResult().getFieldsOrThrow("outcome").getStringValue());

    var list = ListRulesetVersionsRequest.newBuilder().setRuleset(ruleset).setLimit(1);
    var page = management.listRulesetVersions(list.build());
    assertEquals(List.of(1L), page.getItemsList().stream().map(RulesetVersion::getVersion).toList());
    var next = management.listRulesetVersions(list.setCursor(page.getNextCursor()).build());
    assertEquals(List.of(2L), next.getItemsList().stream().map(RulesetVersion::getVersion).toList());

    management.activateRulesetVersion(ActivateRulesetVersionRequest.newBuilder().setRuleset(ruleset).setVersion(2).build());
    assertEquals(2, management.getActiveRulesetVersion(GetActiveRulesetVersionRequest.newBuilder().setRuleset(ruleset).build()).getVersion());
    assertEquals("second", evaluation.evaluate(input).getResult().getFieldsOrThrow("outcome").getStringValue());
    var deletion = DeleteRulesetVersionRequest.newBuilder().setRuleset(ruleset);
    assertStatus(Status.Code.FAILED_PRECONDITION, () -> management.deleteRulesetVersion(deletion.setVersion(2).build()));
    assertTrue(management.deleteRulesetVersion(deletion.setVersion(1).build()).getDeleted());
    assertStatus(Status.Code.NOT_FOUND, () -> evaluation.evaluate(input.toBuilder().setRulesetVersion(1).build()));

    var discarded = create(management, ruleset, sharedNameDrl("discarded"), RulesetMode.STATELESS, 0, 0, false);
    assertEquals(3, discarded.getVersion());
    // Compile this identity before deletion: a reused version would return stale cached rules.
    assertEquals("discarded", evaluation.evaluate(input.toBuilder().setRulesetVersion(3).build()).getResult().getFieldsOrThrow("outcome").getStringValue());
    assertTrue(management.deleteRulesetVersion(deletion.setVersion(3).build()).getDeleted());
    var replacement = create(management, ruleset, sharedNameDrl("replacement"), RulesetMode.STATELESS, 0, 0, true);
    assertEquals(4, replacement.getVersion(), "Deleted versions must never reuse a compiled-rule identity");
    assertEquals("replacement", evaluation.evaluate(input).getResult().getFieldsOrThrow("outcome").getStringValue());

    // Real concurrent RPCs must serialize version allocation, without replacing the active rule.
    try (var callers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var ready = new java.util.concurrent.CountDownLatch(4);
      var start = new java.util.concurrent.CountDownLatch(1);
      var pending = new ArrayList<java.util.concurrent.Future<RulesetVersion>>();
      for (int i = 0; i < 4; i++) {
        int caller = i;
        pending.add(callers.submit(() -> {
          ready.countDown();
          assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
          return create(management, ruleset, sharedNameDrl("concurrent" + caller), RulesetMode.STATELESS, 0, 0, false);
        }));
      }
      try {
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        start.countDown();
      }
      var versions = new java.util.HashSet<Long>();
      for (var future : pending) {
        var created = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(created.getActive());
        assertTrue(versions.add(created.getVersion()), "Concurrent callers must not share a version");
      }
      assertEquals(Set.of(5L, 6L, 7L, 8L), versions);
    }
    assertEquals(4, management.getActiveRulesetVersion(GetActiveRulesetVersionRequest.newBuilder().setRuleset(ruleset).build()).getVersion());
    assertEquals("replacement", evaluation.evaluate(input).getResult().getFieldsOrThrow("outcome").getStringValue());
    for (String invalid : List.of("-1", "not-a-version")) {
      assertStatus(Status.Code.INVALID_ARGUMENT, () -> management.listRulesetVersions(ListRulesetVersionsRequest.newBuilder().setRuleset(ruleset).setCursor(invalid).build()));
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
    verifyStatefulSession(evaluation, dataset, framework, ruleset, session);
  }

  private static void verifyCrossTenantStatefulIsolation(
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub managementA,
      EvaluationServiceGrpc.EvaluationServiceBlockingStub evaluationA,
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub managementB,
      EvaluationServiceGrpc.EvaluationServiceBlockingStub evaluationB,
      String ruleset) {
    GoldenDataset dataset = GoldenDataset.load("transaction-velocity");
    for (var management : List.of(managementA, managementB)) {
      create(
          management,
          ruleset,
          dataset.drl(),
          RulesetMode.STATEFUL,
          dataset.maxWindowSize(),
          dataset.idleTimeoutSeconds(),
          true);
    }
    // Identical ruleset, version and caller session key must still address separate Valkey
    // windows. Tenant B must start empty even after tenant A accumulated enough facts to fire.
    verifyStatefulSession(evaluationA, dataset, "tenant A", ruleset, "same-caller-session");
    verifyStatefulSession(evaluationB, dataset, "tenant B", ruleset, "same-caller-session");
  }

  private static void verifyStatefulSession(
      EvaluationServiceGrpc.EvaluationServiceBlockingStub evaluation,
      GoldenDataset dataset,
      String framework,
      String ruleset,
      String session) {
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

  static RulesetVersion create(
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
