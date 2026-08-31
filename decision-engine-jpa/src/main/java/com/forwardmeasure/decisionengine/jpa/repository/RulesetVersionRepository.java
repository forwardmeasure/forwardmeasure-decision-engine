/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.jpa.repository;

import com.forwardmeasure.decisionengine.jpa.entity.RulesetVersionEntity;
import com.forwardmeasure.jpa.core.repository.AbstractBaseRepository;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public class RulesetVersionRepository extends AbstractBaseRepository<RulesetVersionEntity, Long> {

  public Optional<RulesetVersionEntity> findActive(String ruleset) {
    return entityManager()
        .createQuery(
            "select r from RulesetVersionEntity r where r.ruleset = :ruleset and r.active = true",
            RulesetVersionEntity.class)
        .setParameter("ruleset", ruleset)
        .getResultStream()
        .findFirst();
  }

  public Optional<RulesetVersionEntity> findVersion(String ruleset, long version) {
    return entityManager()
        .createQuery(
            "select r from RulesetVersionEntity r where r.ruleset = :ruleset and r.rulesetVersion ="
                + " :version",
            RulesetVersionEntity.class)
        .setParameter("ruleset", ruleset)
        .setParameter("version", version)
        .getResultStream()
        .findFirst();
  }

  public List<RulesetVersionEntity> list(String ruleset, long afterVersion, int limit) {
    return entityManager()
        .createQuery(
            "select r from RulesetVersionEntity r where r.ruleset = :ruleset and r.rulesetVersion >"
                + " :version order by r.rulesetVersion asc",
            RulesetVersionEntity.class)
        .setParameter("ruleset", ruleset)
        .setParameter("version", afterVersion)
        .setMaxResults(limit)
        .getResultList();
  }

  public long nextVersion(String ruleset) {
    return entityManager()
            .createQuery(
                "select coalesce(max(r.rulesetVersion), 0) from RulesetVersionEntity r where"
                    + " r.ruleset = :ruleset",
                Long.class)
            .setParameter("ruleset", ruleset)
            .getSingleResult()
        + 1;
  }

  public List<RulesetVersionEntity> findActiveForUpdate(String ruleset) {
    return entityManager()
        .createQuery(
            "select r from RulesetVersionEntity r where r.ruleset = :ruleset and r.active = true",
            RulesetVersionEntity.class)
        .setParameter("ruleset", ruleset)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .getResultList();
  }
}
