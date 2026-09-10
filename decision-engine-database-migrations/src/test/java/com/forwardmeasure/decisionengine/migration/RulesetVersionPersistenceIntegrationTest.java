/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forwardmeasure.decisionengine.core.DrlCompiler;
import com.forwardmeasure.decisionengine.core.DroolsRuleEvaluator;
import com.forwardmeasure.decisionengine.domain.EvaluationInput;
import com.forwardmeasure.decisionengine.domain.RulesetMode;
import com.forwardmeasure.decisionengine.domain.RulesetNotFoundException;
import com.forwardmeasure.decisionengine.domain.RulesetVersion;
import com.forwardmeasure.decisionengine.jpa.application.RulesetVersionService;
import com.forwardmeasure.decisionengine.jpa.entity.RulesetVersionEntity;
import com.forwardmeasure.decisionengine.jpa.repository.RulesetVersionRepository;
import com.forwardmeasure.decisionengine.jpa.service.JpaRulesetSource;
import com.forwardmeasure.decisionengine.jpa.service.RulesetVersionServiceImpl;
import com.forwardmeasure.jpa.tenancy.TenantSchema;
import com.forwardmeasure.jpa.testcontainers.WithMigratedTenantSchema;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

/** Deep persistence/application integration coverage against the real migrated Postgres schema. */
@WithMigratedTenantSchema(changelogs = RulesetSchemaMigrator.CHANGELOG)
class RulesetVersionPersistenceIntegrationTest {

  private static final String RULESET_PREFIX = "payments/risk";
  private static final String DRL =
      "package integration;\n"
          + "global java.util.Map result;\n"
          + "rule \"high-risk\"\n"
          + "when\n"
          + "  Map(this[\"risk\"] == \"high\")\n"
          + "then\n"
          + "  result.put(\"outcome\", \"manual_review\");\n"
          + "end\n";

  @Test
  void persistsVersionsAndEvaluatesUsingThePersistedActiveRuleset(
      PostgreSqlTestContainer database, TenantSchema schema) {
    try (Harness harness = new Harness(database, schema)) {
      String ruleset = ruleset();
      RulesetVersion first =
          harness.transaction(
              () ->
                  harness.service.create(
                      ruleset, DRL, RulesetMode.STATELESS, 0, 0, "integration-test", true));

      assertEquals(1, first.version());
      assertTrue(first.active());
      RulesetVersion persistedActive =
          harness.transaction(() -> harness.service.getActive(ruleset));
      assertEquals(first.version(), persistedActive.version());
      assertEquals(DRL, persistedActive.drl());

      var outcome =
          harness.evaluator.evaluate(
              new EvaluationInput(ruleset, null, Map.of("risk", "high"), null));
      assertEquals("manual_review", outcome.result().get("outcome"));
      assertEquals(first.version(), outcome.rulesetVersion());
      assertEquals(List.of("high-risk"), outcome.firedRules());
      assertEquals(1, outcome.factsConsidered());
    }
  }

  @Test
  void supportsVersionSelectionActivationListingAndSafeDeletionAgainstPostgres(
      PostgreSqlTestContainer database, TenantSchema schema) {
    try (Harness harness = new Harness(database, schema)) {
      String ruleset = ruleset();
      RulesetVersion first =
          harness.transaction(
              () ->
                  harness.service.create(
                      ruleset, DRL, RulesetMode.STATELESS, 0, 0, "integration-test", true));
      RulesetVersion second =
          harness.transaction(
              () ->
                  harness.service.create(
                      ruleset, DRL, RulesetMode.STATELESS, 0, 0, "integration-test", false));

      assertEquals(2, second.version());
      assertFalse(second.active());
      assertEquals(
          List.of(1L, 2L),
          harness.transaction(() -> harness.service.list(ruleset, null, 50)).stream()
              .map(RulesetVersion::version)
              .toList());

      RulesetVersion activated =
          harness.transaction(() -> harness.service.activate(ruleset, second.version()));
      assertTrue(activated.active());
      assertEquals(
          second.version(),
          harness.transaction(() -> harness.service.getActive(ruleset)).version());
      assertEquals(
          first.version(),
          harness.transaction(() -> harness.service.get(ruleset, first.version())).version());

      assertTrue(harness.transaction(() -> harness.service.delete(ruleset, first.version())));
      assertThrows(
          RulesetNotFoundException.class,
          () -> harness.transaction(() -> harness.service.get(ruleset, first.version())));
      assertThrows(
          IllegalStateException.class,
          () -> harness.transaction(() -> harness.service.delete(ruleset, second.version())));
    }
  }

  private static String ruleset() {
    return RULESET_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private static final class Harness implements AutoCloseable {
    private final EntityManager entityManager;
    private final SessionFactory sessions;
    private final RulesetVersionService service;
    private final DroolsRuleEvaluator evaluator;

    private Harness(PostgreSqlTestContainer database, TenantSchema schema) {
      Configuration configuration =
          new Configuration()
              .addAnnotatedClass(RulesetVersionEntity.class)
              .setProperty("jakarta.persistence.jdbc.url", database.hostJdbcUrl())
              .setProperty("jakarta.persistence.jdbc.user", database.username())
              .setProperty("jakarta.persistence.jdbc.password", database.password())
              .setProperty("jakarta.persistence.jdbc.driver", "org.postgresql.Driver")
              .setProperty("hibernate.default_schema", schema.value())
              .setProperty("hibernate.hbm2ddl.auto", "none")
              .setProperty("hibernate.show_sql", "false");
      this.sessions = configuration.buildSessionFactory();
      this.entityManager = sessions.createEntityManager();

      RulesetVersionRepository repository = new RulesetVersionRepository();
      repository.bindPersistenceContext(entityManager);
      this.service = new RulesetVersionServiceImpl(repository, new DrlCompiler());
      this.evaluator = new DroolsRuleEvaluator(new JpaRulesetSource(service), null);
    }

    private <T> T transaction(Supplier<T> work) {
      entityManager.getTransaction().begin();
      try {
        T result = work.get();
        entityManager.getTransaction().commit();
        entityManager.clear();
        return result;
      } catch (RuntimeException failure) {
        if (entityManager.getTransaction().isActive()) entityManager.getTransaction().rollback();
        entityManager.clear();
        throw failure;
      }
    }

    @Override
    public void close() {
      entityManager.close();
      sessions.close();
    }
  }
}
