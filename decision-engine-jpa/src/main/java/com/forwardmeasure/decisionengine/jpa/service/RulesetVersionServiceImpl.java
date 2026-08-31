/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.jpa.service;

import com.forwardmeasure.decisionengine.core.DrlCompiler;
import com.forwardmeasure.decisionengine.domain.RulesetMode;
import com.forwardmeasure.decisionengine.domain.RulesetNotFoundException;
import com.forwardmeasure.decisionengine.domain.RulesetVersion;
import com.forwardmeasure.decisionengine.jpa.application.RulesetVersionMapper;
import com.forwardmeasure.decisionengine.jpa.application.RulesetVersionService;
import com.forwardmeasure.decisionengine.jpa.entity.RulesetVersionEntity;
import com.forwardmeasure.decisionengine.jpa.repository.RulesetVersionRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class RulesetVersionServiceImpl implements RulesetVersionService {

  private static final Pattern RULESET_PATTERN =
      Pattern.compile("^[a-z][a-zA-Z0-9]*(/[a-z][a-zA-Z0-9]*)*$");
  private static final Pattern RESULT_GLOBAL =
      Pattern.compile("(?m)\\bglobal\\s+java\\.util\\.Map\\s+result\\s*;");

  private final RulesetVersionRepository repository;
  private final DrlCompiler compiler;

  public RulesetVersionServiceImpl(RulesetVersionRepository repository, DrlCompiler compiler) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.compiler = Objects.requireNonNull(compiler, "compiler");
  }

  @Override
  @Transactional
  public RulesetVersion create(
      String ruleset,
      String drl,
      RulesetMode mode,
      int maxWindowSize,
      int idleTimeoutSeconds,
      String createdBy,
      boolean activate) {
    validate(ruleset, drl, mode, maxWindowSize, idleTimeoutSeconds);
    if (!activate && repository.findActive(ruleset).isEmpty()) {
      throw new IllegalStateException(
          "the first ruleset version must be activated; a ruleset cannot start without an active"
              + " version");
    }
    long version = repository.nextVersion(ruleset);
    var container = compiler.compile(ruleset, version, drl);
    container.dispose();
    RulesetVersionEntity entity = new RulesetVersionEntity();
    entity.setRuleset(ruleset);
    entity.setRulesetVersion(version);
    entity.setDrl(drl);
    entity.setMode(mode);
    entity.setMaxWindowSize(maxWindowSize);
    entity.setIdleTimeoutSeconds(idleTimeoutSeconds);
    entity.setCreatedAt(Instant.now());
    entity.setCreatedBy(createdBy);
    entity.setActive(false);
    repository.persistAndFlush(entity);
    if (activate) {
      return activate(ruleset, version);
    }
    return RulesetVersionMapper.INSTANCE.toDomain(entity);
  }

  @Override
  @Transactional
  public RulesetVersion getActive(String ruleset) {
    return repository
        .findActive(ruleset)
        .map(RulesetVersionMapper.INSTANCE::toDomain)
        .orElseThrow(() -> new RulesetNotFoundException(ruleset));
  }

  @Override
  @Transactional
  public RulesetVersion get(String ruleset, long version) {
    return repository
        .findVersion(ruleset, version)
        .map(RulesetVersionMapper.INSTANCE::toDomain)
        .orElseThrow(() -> new RulesetNotFoundException(ruleset, version));
  }

  @Override
  @Transactional
  public List<RulesetVersion> list(String ruleset, String cursor, int limit) {
    long after = cursor == null || cursor.isBlank() ? 0 : Long.parseLong(cursor);
    int boundedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
    return repository.list(ruleset, after, boundedLimit).stream()
        .map(RulesetVersionMapper.INSTANCE::toDomain)
        .toList();
  }

  @Override
  @Transactional
  public RulesetVersion activate(String ruleset, long version) {
    RulesetVersionEntity requested =
        repository
            .findVersion(ruleset, version)
            .orElseThrow(() -> new RulesetNotFoundException(ruleset, version));
    repository
        .findActiveForUpdate(ruleset)
        .forEach(
            active -> {
              active.setActive(false);
              repository.merge(active);
            });
    requested.setActive(true);
    repository.merge(requested);
    repository.flush();
    return RulesetVersionMapper.INSTANCE.toDomain(requested);
  }

  @Override
  @Transactional
  public boolean delete(String ruleset, long version) {
    RulesetVersionEntity entity =
        repository
            .findVersion(ruleset, version)
            .orElseThrow(() -> new RulesetNotFoundException(ruleset, version));
    if (entity.isActive()) {
      throw new IllegalStateException("cannot delete the active ruleset version");
    }
    repository.delete(entity);
    return true;
  }

  private void validate(
      String ruleset, String drl, RulesetMode mode, int maxWindowSize, int timeout) {
    if (ruleset == null || !RULESET_PATTERN.matcher(ruleset).matches()) {
      throw new IllegalArgumentException("invalid ruleset name");
    }
    if (drl == null || mode == null || RESULT_GLOBAL.matcher(drl).results().count() != 1) {
      throw new IllegalArgumentException(
          "DRL must declare exactly one global java.util.Map result;");
    }
    if (mode == RulesetMode.STATEFUL && (maxWindowSize <= 0 || timeout <= 0)) {
      throw new IllegalArgumentException("stateful fact-window bounds must be positive");
    }
  }
}
