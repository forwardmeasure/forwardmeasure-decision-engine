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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.decisionengine.conformance.fixture.GoldenDataset;
import com.forwardmeasure.decisionengine.contract.v1.ClearCompiledRulesCacheRequest;
import com.forwardmeasure.decisionengine.contract.v1.CreateRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.DecisionEngineAdminServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.GetCacheStatusRequest;
import com.forwardmeasure.decisionengine.contract.v1.GetStatisticsRequest;
import com.forwardmeasure.decisionengine.contract.v1.RulesetManagementServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.RulesetMode;
import com.forwardmeasure.decisionengine.contract.v1.RulesetVersion;
import com.forwardmeasure.decisionengine.contract.v1.UnloadRulesetRequest;
import com.forwardmeasure.decisionengine.contract.v1.WarmRulesetRequest;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;

/** Full black-box conformance test with real databases and all deployable frameworks. */
class DecisionEngineContainerConformanceTest {
  private static final String POSTGRES_IMAGE = "postgres:17-alpine";
  private static final String VALKEY_IMAGE = "valkey/valkey:8.1";
  private static final String MIGRATION_IMAGE =
      "forwardmeasure/decision-engine-database-migration-service:1.0.0-SNAPSHOT";
  private static final Map<String, String> SERVICE_IMAGES =
      Map.of(
          "quarkus", "forwardmeasure/decision-engine-quarkus:1.0.0-SNAPSHOT",
          "spring", "forwardmeasure/decision-engine-spring:1.0.0-SNAPSHOT",
          "micronaut", "forwardmeasure/decision-engine-micronaut:1.0.0-SNAPSHOT");

  @Test
  void allFrameworksEvaluateGoldenDatasetsAgainstPersistentStores() {
    Assumptions.assumeTrue(
        Boolean.getBoolean("decision.engine.conformance.live"),
        "run with -Ddecision.engine.conformance.live=true after building local service images");
    try (Network network = Network.newNetwork();
        PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("decision_engine")
                .withUsername("postgres")
                .withPassword("postgres-password")
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

      verifyFramework("quarkus", quarkus.getMappedPort(9000));
      verifyFramework("spring", spring.getMappedPort(9000));
      verifyFramework("micronaut", micronaut.getMappedPort(9000));
    }
  }

  private static GenericContainer<?> migration(Network network) {
    return new GenericContainer<>(MIGRATION_IMAGE)
        .withNetwork(network)
        .withStartupCheckStrategy(new OneShotStartupCheckStrategy())
        .withEnv("DECISION_ENGINE_DATABASE_URL", "jdbc:postgresql://postgres:5432/decision_engine")
        .withEnv("DECISION_ENGINE_DATABASE_USERNAME", "postgres")
        .withEnv("DECISION_ENGINE_DATABASE_PASSWORD", "postgres-password")
        .withEnv("DECISION_ENGINE_RUNTIME_DATABASE_USERNAME", "decision_engine_runtime")
        .withEnv("DECISION_ENGINE_RUNTIME_DATABASE_PASSWORD", "runtime-password");
  }

  private static GenericContainer<?> service(Network network, String framework) {
    return new GenericContainer<>(SERVICE_IMAGES.get(framework))
        .withNetwork(network)
        .withNetworkAliases("decision-engine-" + framework)
        .withExposedPorts(9000)
        .withEnv("DECISION_ENGINE_DATABASE_URL", "jdbc:postgresql://postgres:5432/decision_engine")
        .withEnv("DECISION_ENGINE_DATABASE_USERNAME", "decision_engine_runtime")
        .withEnv("DECISION_ENGINE_DATABASE_PASSWORD", "runtime-password")
        .withEnv("DECISION_ENGINE_VALKEY_HOST", "valkey")
        .withEnv("DECISION_ENGINE_VALKEY_PORT", "6379")
        .withEnv("DECISION_ENGINE_VALKEY_PASSWORD", "valkey-password")
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofMinutes(2));
  }

  private static void verifyFramework(String framework, int port) {
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    try {
      var management = RulesetManagementServiceGrpc.newBlockingStub(channel);
      var evaluation = EvaluationServiceGrpc.newBlockingStub(channel);
      var admin = DecisionEngineAdminServiceGrpc.newBlockingStub(channel);
      String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

      verifyStatelessDatasets(management, evaluation, admin, suffix);
      verifyStatefulDataset(management, evaluation, framework, suffix);
      assertStatus(
          Status.Code.INVALID_ARGUMENT,
          () -> evaluation.evaluate(request("bad-name", Map.of("value", 1), "")));
      assertStatus(
          Status.Code.NOT_FOUND,
          () -> evaluation.evaluate(request("missing/" + framework, Map.of("value", 1), "")));
      assertTrue(
          admin.getStatistics(GetStatisticsRequest.getDefaultInstance()).getInvocationCount() > 0);
    } finally {
      channel.shutdownNow();
    }
  }

  private static void verifyStatelessDatasets(
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub management,
      EvaluationServiceGrpc.EvaluationServiceBlockingStub evaluation,
      DecisionEngineAdminServiceGrpc.DecisionEngineAdminServiceBlockingStub admin,
      String suffix) {
    for (String fixture : List.of("payments-risk", "customer-segmentation")) {
      GoldenDataset dataset = GoldenDataset.load(fixture);
      String ruleset = dataset.ruleset() + suffix;
      var active = create(management, ruleset, dataset, RulesetMode.STATELESS, true);
      var pinned = create(management, ruleset, dataset, RulesetMode.STATELESS, false);
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
    create(management, ruleset, dataset, RulesetMode.STATEFUL, true);
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
      GoldenDataset dataset,
      RulesetMode mode,
      boolean activate) {
    var response =
        management.createRulesetVersion(
            CreateRulesetVersionRequest.newBuilder()
                .setRuleset(ruleset)
                .setDrl(dataset.drl())
                .setMode(mode)
                .setMaxWindowSize(dataset.maxWindowSize())
                .setIdleTimeoutSeconds(dataset.idleTimeoutSeconds())
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
