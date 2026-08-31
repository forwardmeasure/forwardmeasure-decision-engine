/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.core;

import com.forwardmeasure.decisionengine.domain.EvaluationInput;
import com.forwardmeasure.decisionengine.domain.EvaluationOutcome;
import com.forwardmeasure.decisionengine.domain.FactWindowStore;
import com.forwardmeasure.decisionengine.domain.RuleEvaluationException;
import com.forwardmeasure.decisionengine.domain.RuleEvaluator;
import com.forwardmeasure.decisionengine.domain.RulesetMode;
import com.forwardmeasure.decisionengine.domain.RulesetSource;
import com.forwardmeasure.decisionengine.domain.RulesetVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;

/** Framework-neutral Drools evaluator. */
public final class DroolsRuleEvaluator implements RuleEvaluator {

  private record CacheKey(String ruleset, long version) {}

  private final RulesetSource rulesetSource;
  private final FactWindowStore factWindowStore;
  private final DrlCompiler compiler;
  private final ConcurrentHashMap<CacheKey, KieContainer> containers = new ConcurrentHashMap<>();

  public DroolsRuleEvaluator(RulesetSource rulesetSource, FactWindowStore factWindowStore) {
    this(rulesetSource, factWindowStore, new DrlCompiler());
  }

  DroolsRuleEvaluator(
      RulesetSource rulesetSource, FactWindowStore factWindowStore, DrlCompiler compiler) {
    this.rulesetSource = Objects.requireNonNull(rulesetSource, "rulesetSource");
    this.factWindowStore = factWindowStore;
    this.compiler = Objects.requireNonNull(compiler, "compiler");
  }

  @Override
  public EvaluationOutcome evaluate(EvaluationInput input) {
    Objects.requireNonNull(input, "input");
    RulesetVersion version =
        input.pinnedVersion() == null
            ? rulesetSource.getActiveVersion(input.ruleset())
            : rulesetSource.getVersion(input.ruleset(), input.pinnedVersion());
    if (version.mode() == RulesetMode.STATEFUL
        && (input.sessionKey() == null || input.sessionKey().isBlank())) {
      throw new RuleEvaluationException(
          input.ruleset(),
          version.version(),
          RuleEvaluationException.Reason.INVALID_SESSION_KEY,
          "STATEFUL ruleset called without a session_key");
    }
    KieSession session = null;
    try {
      KieContainer container =
          containers.computeIfAbsent(
              new CacheKey(version.ruleset(), version.version()),
              ignored -> compiler.compile(version.ruleset(), version.version(), version.drl()));
      session = container.newKieSession();
      List<String> firedRules = new ArrayList<>();
      session.addEventListener(
          new DefaultAgendaEventListener() {
            @Override
            public void afterMatchFired(AfterMatchFiredEvent event) {
              firedRules.add(event.getMatch().getRule().getName());
            }
          });

      Map<String, Object> result = new HashMap<>();
      session.setGlobal("result", result);
      List<Map<String, Object>> facts;
      if (version.mode() == RulesetMode.STATEFUL) {
        if (input.sessionKey() == null || input.sessionKey().isBlank()) {
          throw new RuleEvaluationException(
              input.ruleset(),
              version.version(),
              RuleEvaluationException.Reason.INVALID_SESSION_KEY,
              "STATEFUL ruleset called without a session_key");
        }
        if (factWindowStore == null) {
          throw new RuleEvaluationException(
              input.ruleset(),
              version.version(),
              RuleEvaluationException.Reason.FACT_WINDOW_UNAVAILABLE,
              "no FactWindowStore is configured");
        }
        try {
          facts =
              factWindowStore.appendAndLoad(
                  version.ruleset(),
                  version.version(),
                  input.sessionKey(),
                  input.facts(),
                  version.maxWindowSize(),
                  version.idleTimeoutSeconds());
        } catch (RuleEvaluationException exception) {
          throw exception;
        } catch (RuntimeException exception) {
          throw new RuleEvaluationException(
              input.ruleset(),
              version.version(),
              RuleEvaluationException.Reason.FACT_WINDOW_UNAVAILABLE,
              "fact window store is unavailable",
              exception);
        }
      } else {
        facts = List.of(input.facts());
      }
      facts.forEach(session::insert);
      session.fireAllRules();
      if (result.get("outcome") == null) {
        throw new RuleEvaluationException(
            input.ruleset(),
            version.version(),
            RuleEvaluationException.Reason.MISSING_OUTCOME,
            "rules did not produce a non-null outcome");
      }
      return new EvaluationOutcome(
          Map.copyOf(result), List.copyOf(firedRules), version.version(), facts.size());
    } catch (RuleEvaluationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new RuleEvaluationException(
          input.ruleset(),
          version.version(),
          RuleEvaluationException.Reason.EVALUATION_FAILURE,
          "Drools evaluation failed",
          exception);
    } finally {
      if (session != null) {
        session.dispose();
      }
    }
  }
}
