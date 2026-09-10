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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;

/** Framework-neutral Drools evaluator. */
public final class DroolsRuleEvaluator implements RuleEvaluator, RuleEngineAdmin {

  public static final int DEFAULT_CACHE_CAPACITY = 100;

  private record CacheKey(String ruleset, long version) {}

  private final RulesetSource rulesetSource;
  private final FactWindowStore factWindowStore;
  private final DrlCompiler compiler;
  private final int cacheCapacity;
  private final Map<CacheKey, KieContainer> containers;
  private final LongAdder invocationCount = new LongAdder();
  private final LongAdder successCount = new LongAdder();
  private final LongAdder failureCount = new LongAdder();
  private final LongAdder cacheHitCount = new LongAdder();
  private final LongAdder cacheMissCount = new LongAdder();
  private final LongAdder cacheEvictionCount = new LongAdder();
  private final LongAdder totalEvaluationNanos = new LongAdder();
  private final LongAdder totalCompilationNanos = new LongAdder();

  public DroolsRuleEvaluator(RulesetSource rulesetSource, FactWindowStore factWindowStore) {
    this(rulesetSource, factWindowStore, new DrlCompiler(), DEFAULT_CACHE_CAPACITY);
  }

  public DroolsRuleEvaluator(
      RulesetSource rulesetSource,
      FactWindowStore factWindowStore,
      DrlCompiler compiler,
      int cacheCapacity) {
    this.rulesetSource = Objects.requireNonNull(rulesetSource, "rulesetSource");
    this.factWindowStore = factWindowStore;
    this.compiler = Objects.requireNonNull(compiler, "compiler");
    if (cacheCapacity < 1) {
      throw new IllegalArgumentException("cacheCapacity must be greater than zero");
    }
    this.cacheCapacity = cacheCapacity;
    this.containers = new LinkedHashMap<>(cacheCapacity, 0.75f, true);
  }

  DroolsRuleEvaluator(
      RulesetSource rulesetSource, FactWindowStore factWindowStore, DrlCompiler compiler) {
    this(rulesetSource, factWindowStore, compiler, DEFAULT_CACHE_CAPACITY);
  }

  @Override
  public EvaluationOutcome evaluate(EvaluationInput input) {
    long started = System.nanoTime();
    invocationCount.increment();
    try {
      return evaluateInternal(input);
    } catch (RuntimeException exception) {
      failureCount.increment();
      throw exception;
    } finally {
      totalEvaluationNanos.add(System.nanoTime() - started);
    }
  }

  private EvaluationOutcome evaluateInternal(EvaluationInput input) {
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
      KieContainer container = getOrCompile(version);
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
      EvaluationOutcome outcome =
          new EvaluationOutcome(
              Map.copyOf(result), List.copyOf(firedRules), version.version(), facts.size());
      successCount.increment();
      return outcome;
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

  private KieContainer getOrCompile(RulesetVersion version) {
    CacheKey key = new CacheKey(version.ruleset(), version.version());
    synchronized (containers) {
      KieContainer cached = containers.get(key);
      if (cached != null) {
        cacheHitCount.increment();
        return cached;
      }
      cacheMissCount.increment();
      long started = System.nanoTime();
      KieContainer compiled = compiler.compile(version.ruleset(), version.version(), version.drl());
      totalCompilationNanos.add(System.nanoTime() - started);
      containers.put(key, compiled);
      if (containers.size() > cacheCapacity) {
        containers.remove(containers.keySet().iterator().next());
        cacheEvictionCount.increment();
      }
      return compiled;
    }
  }

  @Override
  public RuntimeStatistics statistics() {
    return new RuntimeStatistics(
        invocationCount.sum(),
        successCount.sum(),
        failureCount.sum(),
        cacheHitCount.sum(),
        cacheMissCount.sum(),
        cacheEvictionCount.sum(),
        totalEvaluationNanos.sum(),
        totalCompilationNanos.sum());
  }

  @Override
  public CacheStatus cacheStatus() {
    synchronized (containers) {
      return new CacheStatus(
          cacheCapacity,
          containers.size(),
          containers.keySet().stream()
              .map(key -> new CacheStatus.CacheEntry(key.ruleset(), key.version()))
              .toList());
    }
  }

  @Override
  public boolean unload(String ruleset, long version) {
    synchronized (containers) {
      return containers.remove(new CacheKey(ruleset, version)) != null;
    }
  }

  @Override
  public void clearCache() {
    synchronized (containers) {
      containers.clear();
    }
  }

  @Override
  public CacheStatus warm(String ruleset, long version) {
    getOrCompile(rulesetSource.getVersion(ruleset, version));
    return cacheStatus();
  }

  int cachedContainerCount() {
    synchronized (containers) {
      return containers.size();
    }
  }

  boolean isContainerCached(String ruleset, long version) {
    synchronized (containers) {
      return containers.containsKey(new CacheKey(ruleset, version));
    }
  }
}
