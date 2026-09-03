/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.forwardmeasure.decisionengine.domain.EvaluationInput;
import com.forwardmeasure.decisionengine.domain.FactWindowStore;
import com.forwardmeasure.decisionengine.domain.RuleEvaluationException;
import com.forwardmeasure.decisionengine.domain.RulesetMode;
import com.forwardmeasure.decisionengine.domain.RulesetSource;
import com.forwardmeasure.decisionengine.domain.RulesetVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DroolsRuleEvaluatorTest {

  private static final String APPROVAL_RULE =
      """
      package rules;
      global java.util.Map result;
      rule "approve"
      when
        $fact : java.util.Map( this["approved"] == true )
      then
        result.put("outcome", "approved");
      end
      """;

  @Test
  void evaluatesStatelessRulesAndReportsFiredRule() {
    RulesetVersion version = version(RulesetMode.STATELESS, 1, APPROVAL_RULE);
    DroolsRuleEvaluator evaluator = new DroolsRuleEvaluator(source(version), null);

    var outcome =
        evaluator.evaluate(new EvaluationInput("payments", null, Map.of("approved", true), null));

    assertEquals(Map.of("outcome", "approved"), outcome.result());
    assertEquals(List.of("approve"), outcome.firedRules());
    assertEquals(1, outcome.factsConsidered());
  }

  @Test
  void rejectsEvaluationWhenRulesDoNotProduceAnOutcome() {
    RulesetVersion version =
        version(
            RulesetMode.STATELESS,
            1,
            "global java.util.Map result; rule \"noop\" when eval(true) then end");
    DroolsRuleEvaluator evaluator = new DroolsRuleEvaluator(source(version), null);

    RuleEvaluationException exception =
        assertThrows(
            RuleEvaluationException.class,
            () -> evaluator.evaluate(new EvaluationInput("payments", null, Map.of(), null)));

    assertEquals(RuleEvaluationException.Reason.MISSING_OUTCOME, exception.reason());
  }

  @Test
  void statefulEvaluationUsesVersionScopedFactWindow() {
    RulesetVersion version = version(RulesetMode.STATEFUL, 7, APPROVAL_RULE);
    RecordingFactWindowStore store = new RecordingFactWindowStore();
    DroolsRuleEvaluator evaluator = new DroolsRuleEvaluator(source(version), store);

    var outcome =
        evaluator.evaluate(
            new EvaluationInput("payments", null, Map.of("approved", true), "session-1"));

    assertEquals(1, outcome.factsConsidered());
    assertEquals("payments", store.ruleset);
    assertEquals(7, store.version);
    assertEquals("session-1", store.sessionKey);
  }

  @Test
  void statefulEvaluationRequiresSessionKey() {
    RulesetVersion version = version(RulesetMode.STATEFUL, 1, APPROVAL_RULE);
    DroolsRuleEvaluator evaluator =
        new DroolsRuleEvaluator(
            source(version),
            (ruleset, number, sessionKey, fact, maxWindowSize, idleTimeoutSeconds) -> {
              throw new AssertionError("store must not be called");
            });

    RuleEvaluationException exception =
        assertThrows(
            RuleEvaluationException.class,
            () -> evaluator.evaluate(new EvaluationInput("payments", null, Map.of(), " ")));

    assertEquals(RuleEvaluationException.Reason.INVALID_SESSION_KEY, exception.reason());
  }

  @Test
  void compiledContainerCacheEvictsLeastRecentlyUsedEntry() {
    Map<Long, RulesetVersion> versions =
        Map.of(
            1L, version(RulesetMode.STATELESS, 1, APPROVAL_RULE),
            2L, version(RulesetMode.STATELESS, 2, APPROVAL_RULE),
            3L, version(RulesetMode.STATELESS, 3, APPROVAL_RULE));
    RulesetSource source =
        new RulesetSource() {
          @Override
          public RulesetVersion getActiveVersion(String ruleset) {
            return versions.get(1L);
          }

          @Override
          public RulesetVersion getVersion(String ruleset, long number) {
            return versions.get(number);
          }
        };
    DroolsRuleEvaluator evaluator = new DroolsRuleEvaluator(source, null, new DrlCompiler(), 2);

    evaluator.evaluate(new EvaluationInput("payments", 1L, Map.of("approved", true), null));
    evaluator.evaluate(new EvaluationInput("payments", 2L, Map.of("approved", true), null));
    evaluator.evaluate(new EvaluationInput("payments", 1L, Map.of("approved", true), null));
    evaluator.evaluate(new EvaluationInput("payments", 3L, Map.of("approved", true), null));

    assertEquals(2, evaluator.cachedContainerCount());
    assertFalse(evaluator.isContainerCached("payments", 2));
    assertEquals(true, evaluator.isContainerCached("payments", 1));
    assertEquals(true, evaluator.isContainerCached("payments", 3));
  }

  @Test
  void rejectsNonPositiveCompiledContainerCacheCapacity() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DroolsRuleEvaluator(
                source(version(RulesetMode.STATELESS, 1, APPROVAL_RULE)),
                null,
                new DrlCompiler(),
                0));
  }

  private static RulesetVersion version(RulesetMode mode, long number, String drl) {
    return new RulesetVersion("payments", number, drl, true, mode, 10, 60, null, "test");
  }

  private static RulesetSource source(RulesetVersion version) {
    return new RulesetSource() {
      @Override
      public RulesetVersion getActiveVersion(String ruleset) {
        return version;
      }

      @Override
      public RulesetVersion getVersion(String ruleset, long number) {
        return version;
      }
    };
  }

  private static final class RecordingFactWindowStore implements FactWindowStore {
    private String ruleset;
    private long version;
    private String sessionKey;

    @Override
    public List<Map<String, Object>> appendAndLoad(
        String ruleset,
        long version,
        String sessionKey,
        Map<String, Object> fact,
        int maxWindowSize,
        int idleTimeoutSeconds) {
      this.ruleset = ruleset;
      this.version = version;
      this.sessionKey = sessionKey;
      return new ArrayList<>(List.of(fact));
    }
  }
}
