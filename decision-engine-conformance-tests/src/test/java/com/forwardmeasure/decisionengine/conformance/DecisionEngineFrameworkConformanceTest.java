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

import com.forwardmeasure.decisionengine.contract.v1.CreateRulesetVersionRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.RulesetManagementServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.RulesetMode;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Black-box contract test against the three locally running framework containers. */
class DecisionEngineFrameworkConformanceTest {
  private static final Map<String, Integer> ENDPOINTS =
      Map.of("quarkus", 19000, "spring", 19001, "micronaut", 19002);
  private static final String STATELESS =
      "package rules; global java.util.Map result; rule \"allow\" when Map() then"
          + " result.put(\"outcome\", \"allowed\"); end";
  private static final String STATEFUL =
      "package rules; global java.util.Map result; rule \"third\" when Number(intValue >= 3) from"
          + " accumulate( Map(), count(1) ) then result.put(\"outcome\", \"third\"); end";
  private static ManagedChannel channel;

  @BeforeAll
  static void requireContainers() {
    // The live profile is deliberately explicit: these tests must never silently become unit tests.
    Assumptions.assumeTrue(
        Boolean.getBoolean("decision.engine.conformance.live"),
        "run with -Ddecision.engine.conformance.live=true against ports 19000,19001,19002");
  }

  @AfterAll
  static void closeChannel() {
    if (channel != null) channel.shutdownNow();
  }

  @Test
  void allFrameworksExposeTheSameContract() {
    ENDPOINTS.forEach(this::verifyFramework);
  }

  private void verifyFramework(String framework, int port) {
    channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    var management = RulesetManagementServiceGrpc.newBlockingStub(channel);
    var evaluation = EvaluationServiceGrpc.newBlockingStub(channel);
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String stateless = "conf/" + framework + "s" + suffix;
    String stateful = "conf/" + framework + "f" + suffix;

    create(management, stateless, STATELESS, RulesetMode.STATELESS);
    awaitActive(management, stateless);
    var statelessResponse = evaluation.evaluate(request(stateless, 1, ""));
    assertEquals(
        "allowed", statelessResponse.getResult().getFieldsOrThrow("outcome").getStringValue());
    assertEquals(1, statelessResponse.getFactsConsidered());

    create(management, stateful, STATEFUL, RulesetMode.STATEFUL);
    awaitActive(management, stateful);
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> evaluation.evaluate(request(stateful, 1, framework)));
    assertStatus(
        Status.Code.FAILED_PRECONDITION,
        () -> evaluation.evaluate(request(stateful, 2, framework)));
    var third = evaluation.evaluate(request(stateful, 3, framework));
    assertEquals("third", third.getResult().getFieldsOrThrow("outcome").getStringValue());
    assertEquals(3, third.getFactsConsidered());

    assertStatus(
        Status.Code.INVALID_ARGUMENT, () -> evaluation.evaluate(request("bad-name", 1, "")));
    assertStatus(
        Status.Code.NOT_FOUND, () -> evaluation.evaluate(request("missing" + framework, 1, "")));
    assertStatus(Status.Code.INVALID_ARGUMENT, () -> evaluation.evaluate(request(stateful, 1, "")));
  }

  private static void create(
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub management,
      String ruleset,
      String drl,
      RulesetMode mode) {
    var response =
        management.createRulesetVersion(
            CreateRulesetVersionRequest.newBuilder()
                .setRuleset(ruleset)
                .setDrl(drl)
                .setMode(mode)
                .setActivate(true)
                .setMaxWindowSize(10)
                .setIdleTimeoutSeconds(300)
                .setCreatedBy("conformance")
                .build());
    assertTrue(response.getActive());
  }

  private static void awaitActive(
      RulesetManagementServiceGrpc.RulesetManagementServiceBlockingStub management,
      String ruleset) {
    for (int attempt = 0; attempt < 30; attempt++) {
      try {
        if (management
            .getActiveRulesetVersion(
                com.forwardmeasure.decisionengine.contract.v1.GetActiveRulesetVersionRequest
                    .newBuilder()
                    .setRuleset(ruleset)
                    .build())
            .getActive()) return;
      } catch (StatusRuntimeException ignored) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError("interrupted while waiting for active version", interrupted);
        }
      }
    }
    throw new AssertionError("active version did not become visible: " + ruleset);
  }

  private static EvaluationRequest request(String ruleset, int call, String session) {
    Struct input =
        Struct.newBuilder()
            .putFields("call", Value.newBuilder().setNumberValue(call).build())
            .build();
    return EvaluationRequest.newBuilder()
        .setRuleset(ruleset)
        .setInput(input)
        .setSessionKey(session)
        .build();
  }

  private static void assertStatus(Status.Code expected, Runnable invocation) {
    try {
      invocation.run();
      throw new AssertionError("expected " + expected);
    } catch (StatusRuntimeException failure) {
      assertEquals(expected, failure.getStatus().getCode());
    }
  }
}
