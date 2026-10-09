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

import static com.forwardmeasure.datastreaming.launcher.application.fowf.PublicWorkflowAcceptanceClient.endpoint;
import static com.forwardmeasure.datastreaming.launcher.application.fowf.PublicWorkflowAcceptanceClient.publish;
import static com.forwardmeasure.datastreaming.launcher.application.fowf.PublicWorkflowAcceptanceClient.request;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.TEST_CLIENT_SECRET;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.create;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.migration;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.service;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.sharedNameDrl;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.token;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.withBearer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.forwardmeasure.authzen.testkit.AuthzenKeycloakFixture;
import com.forwardmeasure.datastreaming.launcher.application.fowf.RealFowfWorkflowFixture;
import com.forwardmeasure.decisionengine.contract.v1.RulesetManagementServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.RulesetMode;
import com.forwardmeasure.testcontainers.valkey.ValkeyTestContainer;
import io.grpc.ManagedChannelBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.images.builder.Transferable;

/** Actual public FOWF admission -> operation adapter -> authenticated FDE gRPC -> real Valkey. */
class DecisionEngineWorkflowRuntimeAcceptanceTest {
  private static final String AUDIENCE = "decision-engine-api";

  record Runtime(
      RealFowfWorkflowFixture.Framework framework,
      String engine,
      RealFowfWorkflowFixture.PekkoPersistence persistence) {}

  static Stream<Runtime> runtimes() {
    var all =
        Stream.of(RealFowfWorkflowFixture.Framework.values())
            .flatMap(
                framework ->
                    Stream.of(
                        new Runtime(
                            framework,
                            "kafka-streams",
                            RealFowfWorkflowFixture.PekkoPersistence.POSTGRESQL),
                        new Runtime(
                            framework,
                            "pekko",
                            RealFowfWorkflowFixture.PekkoPersistence.POSTGRESQL),
                        new Runtime(
                            framework,
                            "pekko",
                            RealFowfWorkflowFixture.PekkoPersistence.CASSANDRA)))
            .toList();
    String selection = System.getProperty("fowf.acceptance.runtime", "");
    var selected =
        all.stream()
            .filter(
                runtime ->
                    selection.isBlank()
                        || selection.equals(
                            runtime.framework().name().toLowerCase(Locale.ROOT)
                                + "/"
                                + runtime.engine()
                                + "/"
                                + runtime.persistence().name().toLowerCase(Locale.ROOT)))
            .toList();
    if (selected.isEmpty())
      throw new IllegalArgumentException("Unknown workflow runtime: " + selection);
    return selected.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("runtimes")
  @Timeout(900)
  void productionWorkflowEvaluatesAndPreservesStateAcrossServiceRestart(Runtime selected)
      throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("decision.engine.conformance.live"),
        "Requires full-suite and current locally built FDE/FOWF images");
    String alias = "decision-" + UUID.randomUUID();
    String otherAlias = "isolated-" + UUID.randomUUID();
    String framework = selected.framework().name().toLowerCase(Locale.ROOT);
    try (var runtime =
            RealFowfWorkflowFixture.startWithNetworkIdentity(
                alias, "workflow-caller", selected.framework(), selected.persistence());
        var valkey =
            new ValkeyTestContainer(runtime.network(), "valkey", "valkey-password").start()) {
      var identity = runtime.keycloak();
      String otherOrganization = runtime.provisionAdditionalTenant(otherAlias, "workflow-caller");
      for (String organization : List.of(runtime.organizationId(), otherOrganization)) {
        identity.grantOrganizationClientRole(organization, "decision-admin");
        identity.grantResourceAuthorization(
            organization,
            "decision-engine",
            "rulesets",
            "fde-workflow-acceptance",
            "decision-admin",
            Set.of("decision:evaluate", "decision:manage", "decision:admin"));
      }
      identity.grantOrganizationClientRole(runtime.organizationId(), "decision-denied");
      var postgres = runtime.postgres();
      try (var migration =
              migration(runtime.network())
                  .withEnv("DECISION_ENGINE_CONTROL_PLANE_DATABASE_URL", postgres.networkJdbcUrl())
                  .withEnv("DECISION_ENGINE_ADMIN_DATABASE_USERNAME", postgres.username())
                  .withEnv("DECISION_ENGINE_ADMIN_DATABASE_PASSWORD", postgres.password())
                  .withEnv("DECISION_ENGINE_TENANTS", alias + ":A," + otherAlias + ":B");
          var decision =
              service(runtime.network(), framework, identity)
                  .withEnv("DECISION_ENGINE_VALKEY_PASSWORD", valkey.password())
                  .withEnv(
                      "DECISION_ENGINE_CONTROL_PLANE_DATABASE_URL", postgres.networkJdbcUrl())) {
        migration.start();
        assertEquals(0L, migration.getCurrentContainerInfo().getState().getExitCodeLong());
        decision.start();
        var execution = runtime.startExecutionManagement(selected.engine());
        boolean pekko = selected.engine().equals("pekko");
        var engine = pekko ? runtime.startEnginePekko() : runtime.startEngineKafkaStreams();
        var definitions = runtime.startDefinitionManagement();
        String allowed =
            token(
                identity, "decision-allowed", runtime.organizationId(), "decision-admin", AUDIENCE);
        var adapter =
            runtime.startAuthenticatedGrpcAdapter(pekko, "decision-engine-" + framework, allowed);
        if (pekko) runtime.awaitPekkoClusterReady(engine, adapter);
        // Source contracts come from the production API specification module via test resources.
        String proto =
            runtime.startDocumentServer(
                "/evaluation.proto",
                "text/x-protobuf",
                resource("/META-INF/proto/forwardmeasure/decisionengine/v1/evaluation.proto"));
        String workflow =
            resource("/META-INF/decision-engine/workflows/evaluate.yaml")
                .replace("@BUNDLE_DID@/evaluation.proto", proto)
                .replace("@FDE_HOST@", "decision-engine-" + framework);
        String caller =
            token(
                identity,
                "workflow-allowed",
                runtime.organizationId(),
                "workflow-caller",
                AuthzenKeycloakFixture.CLIENT_ID);
        UUID revision = publish(definitions, caller, workflow);
        var channel =
            ManagedChannelBuilder.forAddress(decision.getHost(), decision.getMappedPort(9000))
                .usePlaintext()
                .build();
        long version;
        try {
          var management =
              withBearer(
                  RulesetManagementServiceGrpc.newBlockingStub(channel),
                  identity.clientCredentialsToken("decision-allowed", TEST_CLIENT_SECRET));
          version =
              create(
                      management,
                      "acceptance/workflow",
                      sharedNameDrl("accepted"),
                      RulesetMode.STATEFUL,
                      10,
                      600,
                      true)
                  .getVersion();
        } finally {
          channel.shutdownNow();
        }
        String api = endpoint(execution);
        // The public timer view must be populated by the packaged engine, not synthetic events.
        UUID waitRevision =
            publish(
                definitions,
                caller,
                """
                document: {dsl: '1.0.3', namespace: acceptance, name: activity-wait, version: '1.0.0'}
                do:
                  - pause: {wait: PT1S}
                  - finish: {set: {finished: true}}
                """);
        JsonNode waited = invoke(runtime, api, waitRevision, Map.of());
        assertEquals("COMPLETED", waited.path("state").asText());
        JsonNode timers = awaitActivities(runtime, api, waited, "timers", "FIRED");
        assertTrue(timers.get(0).path("taskPath").asText().contains("pause"));
        assertTrue(
            java.time.Instant.parse(timers.get(0).path("dueAt").asText())
                .isAfter(java.time.Instant.parse(timers.get(0).path("scheduledAt").asText())));
        System.out.println("FDE scenario passed: real workflow timer projection");
        String session = UUID.randomUUID().toString();
        Map<String, Object> input =
            Map.of(
                "ruleset",
                "acceptance/workflow",
                "rulesetVersion",
                version,
                "input",
                Map.of("go", true),
                "sessionKey",
                session,
                "correlationId",
                session);
        String secretPath = "/var/run/secrets/openworkflow/" + alias + "/decision-engine-token";
        for (int count = 1; count <= 2; count++) {
          mountToken(
              adapter,
              secretPath,
              identity.clientCredentialsToken("decision-allowed", TEST_CLIENT_SECRET));
          JsonNode result = invoke(runtime, api, revision, input);
          assertEquals("COMPLETED", result.path("state").asText(), result.toString());
          JsonNode effects = awaitActivities(runtime, api, result, "effects", "COMPLETED");
          assertEquals(1, effects.size(), "One gRPC invocation must produce one durable effect");
          assertTrue(effects.get(0).path("taskPath").asText().contains("evaluate"));
          assertFalse(effects.toString().contains(secretPath));
          assertFalse(effects.toString().contains("authorization"));
          assertEquals("accepted", result.path("output").path("result").path("outcome").asText());
          assertEquals(count, result.path("output").path("factsConsidered").asInt());
          assertEquals(version, result.path("output").path("rulesetVersion").asLong());
          assertEquals(session, result.path("output").path("correlationId").asText());
          assertFalse(result.path("output").path("firedRules").isEmpty());
          System.out.println(
              "FDE scenario passed: authorized evaluation and effect projection " + count);
          if (count == 1) {
            decision.getDockerClient().restartContainerCmd(decision.getContainerId()).exec();
            awaitReady(decision);
          }
        }
        // A valid but denied identity must reach the real FDE authorization boundary and fail.
        String denied =
            token(
                identity, "decision-denied", runtime.organizationId(), "decision-denied", AUDIENCE);
        mountToken(adapter, secretPath, denied);
        JsonNode rejected = invoke(runtime, api, revision, input);
        assertEquals("FAILED", rejected.path("state").asText(), rejected.toString());
        assertTrue(
            rejected.path("error").toString().contains("PERMISSION_DENIED"), rejected.toString());
        System.out.println("FDE scenario passed: denied identity rejected");
        // An authorized identity in B must not resolve A's ruleset or accumulate A's facts.
        String isolated =
            token(identity, "decision-isolated", otherOrganization, "decision-admin", AUDIENCE);
        mountToken(adapter, secretPath, isolated);
        JsonNode hidden = invoke(runtime, api, revision, input);
        assertEquals("FAILED", hidden.path("state").asText(), hidden.toString());
        assertTrue(hidden.path("error").toString().contains("NOT_FOUND"), hidden.toString());
        System.out.println("FDE scenario passed: tenant isolation");
        mountToken(
            adapter,
            secretPath,
            identity.clientCredentialsToken("decision-allowed", TEST_CLIENT_SECRET));
        JsonNode afterDenials = invoke(runtime, api, revision, input);
        assertEquals("COMPLETED", afterDenials.path("state").asText(), afterDenials.toString());
        assertEquals(
            3,
            afterDenials.path("output").path("factsConsidered").asInt(),
            "Rejected calls must not modify the allowed tenant's fact window");
      }
    }
  }

  private static JsonNode awaitActivities(
      RealFowfWorkflowFixture runtime,
      String api,
      JsonNode execution,
      String collection,
      String state)
      throws Exception {
    String id = execution.path("id").asText();
    assertFalse(id.isBlank());
    var observed = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> {
              JsonNode details =
                  request(
                      api,
                      "/v1/workflow-executions/" + id,
                      runtime
                          .keycloak()
                          .clientCredentialsToken("workflow-allowed", TEST_CLIENT_SECRET),
                      "GET",
                      null,
                      null,
                      200);
              JsonNode activities = details.path(collection);
              assertFalse(activities.isEmpty(), "Engine journal must populate " + collection);
              for (JsonNode activity : activities) {
                assertEquals(state, activity.path("state").asText(), activity.toString());
                UUID.fromString(
                    activity.path(collection.equals("timers") ? "timerId" : "effectId").asText());
                java.time.Instant.parse(
                    activity
                        .path(collection.equals("timers") ? "resolvedAt" : "completedAt")
                        .asText());
              }
              observed.set(activities);
            });
    return observed.get();
  }

  private static void awaitReady(org.testcontainers.containers.GenericContainer<?> service) {
    var channel =
        ManagedChannelBuilder.forAddress(service.getHost(), service.getMappedPort(9000))
            .usePlaintext()
            .build();
    try {
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(60))
          .ignoreExceptionsMatching(
              failure ->
                  failure instanceof io.grpc.StatusRuntimeException status
                      && (status.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE
                          || status.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED))
          .untilAsserted(
              () ->
                  assertEquals(
                      io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING,
                      io.grpc.health.v1.HealthGrpc.newBlockingStub(channel)
                          .withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS)
                          .check(
                              io.grpc.health.v1.HealthCheckRequest.newBuilder()
                                  .setService("readiness")
                                  .build())
                          .getStatus()));
    } finally {
      channel.shutdownNow();
    }
  }

  private static void mountToken(
      org.testcontainers.containers.GenericContainer<?> adapter, String path, String token) {
    // A synthetic short-lived fixture credential, supplied through the production secret mount.
    // Never put it into workflow input, definitions, output assertions or logs.
    adapter.copyFileToContainer(
        Transferable.of(token.getBytes(StandardCharsets.UTF_8), 0444), path);
  }

  private static JsonNode invoke(
      RealFowfWorkflowFixture runtime, String api, UUID revision, Map<String, Object> input)
      throws Exception {
    String token =
        runtime.keycloak().clientCredentialsToken("workflow-allowed", TEST_CLIENT_SECRET);
    var admitted =
        request(
            api,
            "/v1/workflow-executions",
            token,
            "POST",
            Map.of("revisionId", revision, "input", input),
            UUID.randomUUID().toString(),
            202);
    String id = admitted.path("id").asText();
    assertFalse(id.isBlank());
    long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
    JsonNode last = null;
    while (System.nanoTime() < deadline) {
      last =
          request(
              api,
              "/v1/workflow-executions/" + id,
              runtime.keycloak().clientCredentialsToken("workflow-allowed", TEST_CLIENT_SECRET),
              "GET",
              null,
              null,
              200);
      if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(last.path("state").asText()))
        return last;
      Thread.sleep(500);
    }
    throw new AssertionError("FOWF did not finish the FDE evaluation: " + last);
  }

  private static String resource(String path) throws Exception {
    try (var input =
        Objects.requireNonNull(
            DecisionEngineWorkflowRuntimeAcceptanceTest.class.getResourceAsStream(path), path)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
