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

import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.TEST_CLIENT_SECRET;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.create;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.image;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.migration;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.registryMigration;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.sharedNameDrl;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.token;
import static com.forwardmeasure.decisionengine.conformance.DecisionEngineContainerConformanceTest.withBearer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forwardmeasure.authzen.testkit.AuthzenKeycloakFixture;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationRequest;
import com.forwardmeasure.decisionengine.contract.v1.EvaluationServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.RulesetManagementServiceGrpc;
import com.forwardmeasure.decisionengine.contract.v1.RulesetMode;
import com.forwardmeasure.jpa.tenancy.Did;
import com.forwardmeasure.testcontainers.kubernetes.KubernetesTestContainer;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlContainerConfiguration;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import com.forwardmeasure.testcontainers.valkey.ValkeyTestContainer;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.grpc.ManagedChannelBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Network;

/** Actual product Helmfile deployment, including its embedded/external Valkey release decision. */
class DecisionEngineValkeyDeploymentAcceptanceTest {
  private static final String NAMESPACE = "fde-acceptance";

  static Stream<Arguments> deployments() {
    String selection = System.getProperty("decision.engine.acceptance.deployment", "");
    var selected =
        Stream.of("quarkus", "spring", "micronaut")
            .flatMap(
                framework ->
                    Stream.of("embedded", "external")
                        .filter(
                            mode -> selection.isBlank() || selection.equals(framework + "/" + mode))
                        .map(mode -> Arguments.of(framework, mode)))
            .toList();
    if (selected.isEmpty())
      throw new IllegalArgumentException("Unknown FDE deployment: " + selection);
    return selected.stream();
  }

  @ParameterizedTest(name = "{0}/{1}")
  @MethodSource("deployments")
  @Timeout(900)
  void renderedDeploymentUsesAuthenticatedValkeyAndRetainsFactsOnPodReplacement(
      String framework, String mode) throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("decision.engine.conformance.live"),
        "Requires full-suite, Helmfile/Helm/kubectl and current local FDE images");
    try (var network = Network.newNetwork();
        var identity = AuthzenKeycloakFixture.start(network, "keycloak");
        var postgres =
            new PostgreSqlTestContainer(
                    new PostgreSqlContainerConfiguration(
                            PostgreSqlContainerConfiguration.DEFAULT_IMAGE,
                            "postgres",
                            "postgres",
                            "postgres-password",
                            Optional.empty(),
                            List.of(),
                            PostgreSqlContainerConfiguration.DEFAULT_MEMORY_BYTES,
                            PostgreSqlContainerConfiguration.DEFAULT_MEMORY_SWAP_BYTES)
                        .withNetwork(network.getId(), List.of("postgres")))
                .start();
        var external = new ValkeyTestContainer(network, "valkey", "valkey-password");
        var registry = registryMigration(network);
        var migration = migration(network);
        var cluster = new KubernetesTestContainer().withNetwork(network, "k3s").start();
        var client = cluster.createClient()) {
      if (mode.equals("external")) external.start();
      String organization =
          identity.provisionTenant(
              "tenant-a", Did.parse("did:web:tenant-a.conformance.test"), "decision-admin");
      identity.grantResourceAuthorization(
          organization,
          "decision-engine",
          "rulesets",
          "deployment-admin",
          "decision-admin",
          Set.of("decision:evaluate", "decision:manage", "decision:admin"));
      registry.withEnv("OPENWORKFLOW_CONTROL_PLANE_DATABASE_URL", postgres.networkJdbcUrl());
      migration.withEnv("DECISION_ENGINE_CONTROL_PLANE_DATABASE_URL", postgres.networkJdbcUrl());
      registry.start();
      migration.start();
      assertEquals(0L, registry.getCurrentContainerInfo().getState().getExitCodeLong());
      assertEquals(0L, migration.getCurrentContainerInfo().getState().getExitCodeLong());
      // All configuration belongs to this newly created cluster, never the operator's kubecontext.
      addFixtureDns(client, network);
      client
          .namespaces()
          .resource(
              new NamespaceBuilder().withNewMetadata().withName(NAMESPACE).endMetadata().build())
          .create();
      secret(
          client,
          "fde-db",
          Map.of("username", "decision_engine_runtime", "password", "runtime-password"));
      secret(client, "fde-valkey", Map.of("password", "valkey-password"));
      secret(
          client,
          "decision-engine-identity-credentials",
          Map.of("DECISION_ENGINE_CLIENT_SECRET", AuthzenKeycloakFixture.AUTHZEN_CLIENT_SECRET));
      String pinned = cluster.loadImageAndPinDigest(image(framework));
      String repository = pinned.substring(0, pinned.indexOf('@'));
      int colon = repository.lastIndexOf(':');
      if (colon > repository.lastIndexOf('/')) repository = repository.substring(0, colon);
      String digest = pinned.substring(pinned.indexOf('@') + 1);
      Path directory = Files.createTempDirectory("fde-valkey-" + mode + "-");
      Path kubeconfig = directory.resolve("kubeconfig");
      Files.writeString(kubeconfig, cluster.kubeConfigYaml());
      Path values = directory.resolve("fixture-values.json");
      var platform =
          Map.of(
              "framework", framework,
              "products", Map.of("decisionEngine", Map.of("enabled", true)),
              "namespaces", Map.of("decisionEngine", NAMESPACE),
              "secrets",
                  Map.of("decisionEngineDatabase", "fde-db", "decisionEngineValkey", "fde-valkey"),
              "endpoints",
                  Map.of("keycloak", Map.of("issuer", identity.networkIssuer().toString())),
              "identity",
                  Map.of(
                      "serviceClients",
                      Map.of(
                          "decisionEngine",
                          Map.of("clientId", AuthzenKeycloakFixture.AUTHZEN_CLIENT_ID))));
      Files.writeString(
          values,
          new ObjectMapper()
              .writeValueAsString(
                  Map.of(
                      "platform", platform,
                      "database",
                          Map.of(
                              "url",
                              "jdbc:postgresql://postgres:5432/postgres",
                              "credentialsSecret",
                              "fde-db"),
                      "cloudSqlProxy", Map.of("enabled", false),
                      "factWindow",
                          Map.of(
                              "valkey",
                              Map.of(
                                  "mode",
                                  mode,
                                  "external",
                                  Map.of(
                                      "host",
                                      "valkey",
                                      "port",
                                      6379,
                                      "credentialsSecret",
                                      "fde-valkey"))),
                      "imageVersions",
                          Map.of(
                              "decisionEngine",
                              Map.of(
                                  "repositories",
                                  Map.of(framework, repository),
                                  "tag",
                                  image(framework).substring(image(framework).lastIndexOf(':') + 1),
                                  "digests",
                                  Map.of(framework, digest),
                                  "pullPolicy",
                                  "IfNotPresent")))));
      Path product = productDirectory();
      Path manifest = directory.resolve("deployment.yaml");
      run(
          directory,
          manifest,
          "helmfile",
          "--file",
          product.resolve("deploy/helmfile/helmfile.yaml.gotmpl").toString(),
          "--environment",
          "base",
          "--state-values-file",
          values.toString(),
          "--selector",
          "component=server",
          "--selector",
          "component=fact-window",
          "template");
      String rendered = Files.readString(manifest);
      assertTrue(rendered.contains(digest), "The selected current image must be rendered");
      assertEquals(
          mode.equals("embedded"),
          rendered.contains("decision-engine-valkey." + NAMESPACE),
          "The actual Helmfile must select the expected Valkey endpoint");
      run(
          directory,
          directory.resolve("apply.log"),
          "kubectl",
          "--kubeconfig",
          kubeconfig.toString(),
          "--namespace",
          NAMESPACE,
          "apply",
          "-f",
          manifest.toString());
      awaitDeployment(client);
      try (var forward =
          client.services().inNamespace(NAMESPACE).withName("decision-engine").portForward(9000)) {
        var channel =
            ManagedChannelBuilder.forAddress("127.0.0.1", forward.getLocalPort())
                .usePlaintext()
                .build();
        try {
          String caller =
              token(
                  identity,
                  "deployment-caller",
                  organization,
                  "decision-admin",
                  "decision-engine-api");
          var management =
              withBearer(RulesetManagementServiceGrpc.newBlockingStub(channel), caller);
          create(
              management,
              "deployment/stateful",
              sharedNameDrl("retained"),
              RulesetMode.STATEFUL,
              10,
              600,
              true);
          EvaluationRequest request =
              EvaluationRequest.newBuilder()
                  .setRuleset("deployment/stateful")
                  .setSessionKey("pod-replacement")
                  .setInput(
                      Struct.newBuilder()
                          .putFields("go", Value.newBuilder().setBoolValue(true).build()))
                  .build();
          var first =
              withBearer(EvaluationServiceGrpc.newBlockingStub(channel), caller).evaluate(request);
          assertEquals(1, first.getFactsConsidered());
          assertEquals("retained", first.getResult().getFieldsOrThrow("outcome").getStringValue());
          client
              .pods()
              .inNamespace(NAMESPACE)
              .withLabel("app.kubernetes.io/instance", "decision-engine")
              .delete();
        } finally {
          channel.shutdownNow();
        }
      }
      awaitDeployment(client);
      // A new port-forward/channel must attach to the replacement pod, not the terminated one.
      try (var forward =
          client.services().inNamespace(NAMESPACE).withName("decision-engine").portForward(9000)) {
        var channel =
            ManagedChannelBuilder.forAddress("127.0.0.1", forward.getLocalPort())
                .usePlaintext()
                .build();
        try {
          var evaluation =
              withBearer(
                  EvaluationServiceGrpc.newBlockingStub(channel),
                  identity.clientCredentialsToken("deployment-caller", TEST_CLIENT_SECRET));
          var result =
              evaluation.evaluate(
                  EvaluationRequest.newBuilder()
                      .setRuleset("deployment/stateful")
                      .setSessionKey("pod-replacement")
                      .setInput(
                          Struct.newBuilder()
                              .putFields("go", Value.newBuilder().setBoolValue(true).build()))
                      .build());
          assertEquals(
              2, result.getFactsConsidered(), "Valkey facts must survive FDE pod replacement");
          assertEquals("retained", result.getResult().getFieldsOrThrow("outcome").getStringValue());
        } finally {
          channel.shutdownNow();
        }
      }
    }
  }

  private static Path productDirectory() {
    for (String start :
        List.of(
            System.getProperty("maven.multiModuleProjectDirectory", System.getProperty("user.dir")),
            System.getProperty("user.dir"))) {
      for (Path directory = Path.of(start).toAbsolutePath();
          directory != null;
          directory = directory.getParent()) {
        if (Files.isRegularFile(directory.resolve("decision-engine-api-specifications/pom.xml"))
            && Files.isRegularFile(directory.resolve("deploy/helmfile/helmfile.yaml.gotmpl"))) {
          return directory;
        }
      }
    }
    throw new IllegalStateException("Cannot locate the Decision Engine product Helmfile");
  }

  private static void secret(KubernetesClient client, String name, Map<String, String> values) {
    client
        .secrets()
        .inNamespace(NAMESPACE)
        .resource(
            new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withStringData(values)
                .build())
        .create();
  }

  private static void addFixtureDns(KubernetesClient client, Network network) {
    var docker = DockerClientFactory.instance().client();
    var members = docker.inspectNetworkCmd().withNetworkId(network.getId()).exec().getContainers();
    StringBuilder hosts = new StringBuilder();
    for (String containerId : members.keySet()) {
      var connection =
          docker.inspectContainerCmd(containerId).exec().getNetworkSettings().getNetworks();
      for (var attachment : connection.values()) {
        if (attachment.getAliases() == null) continue;
        for (String alias : List.of("postgres", "keycloak", "valkey")) {
          if (attachment.getAliases().contains(alias))
            hosts.append(attachment.getIpAddress()).append(' ').append(alias).append('\n');
        }
      }
    }
    var config = client.configMaps().inNamespace("kube-system").withName("coredns").get();
    config
        .getData()
        .put("NodeHosts", config.getData().getOrDefault("NodeHosts", "") + "\n" + hosts);
    client.configMaps().inNamespace("kube-system").resource(config).update();
  }

  private static void awaitDeployment(KubernetesClient client) {
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofMinutes(4))
        .untilAsserted(
            () -> {
              var deployment =
                  client
                      .apps()
                      .deployments()
                      .inNamespace(NAMESPACE)
                      .withName("decision-engine")
                      .get();
              assertTrue(
                  deployment != null
                      && deployment.getStatus() != null
                      && Integer.valueOf(1).equals(deployment.getStatus().getReadyReplicas()));
              var pods =
                  client
                      .pods()
                      .inNamespace(NAMESPACE)
                      .withLabel("app.kubernetes.io/instance", "decision-engine")
                      .list()
                      .getItems();
              assertEquals(1, pods.size());
              assertTrue(pods.get(0).getMetadata().getDeletionTimestamp() == null);
              assertTrue(pods.get(0).getStatus() != null);
              assertTrue(pods.get(0).getStatus().getConditions() != null);
              assertTrue(
                  pods.get(0).getStatus().getConditions().stream()
                      .anyMatch(
                          condition ->
                              condition.getType().equals("Ready")
                                  && condition.getStatus().equals("True")));
            });
  }

  private static void run(Path directory, Path output, String... command) throws Exception {
    Process process =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(4, TimeUnit.MINUTES), "Fixture command timed out: " + command[0]);
      assertEquals(
          0, process.exitValue(), "Fixture command failed: " + command[0] + "; output: " + output);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    assertFalse(Files.notExists(output));
  }
}
