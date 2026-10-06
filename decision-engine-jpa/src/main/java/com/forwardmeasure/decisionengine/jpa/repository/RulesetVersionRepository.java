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

  /** Serialize create/activation/deletion, including the first version when no row exists. */
  public void lockRuleset(String ruleset) {
    entityManager()
        .createNativeQuery(
            "select 1 from pg_advisory_xact_lock(hashtextextended(current_database() || ':' ||"
                + " current_schema() || ':' || :ruleset, 0))",
            Integer.class)
        .setParameter("ruleset", ruleset)
        .getSingleResult();
  }

  /** Durable counter: deleting the largest version must never reuse its cache/window identity. */
  public long nextVersion(String ruleset) {
    return ((Number)
            entityManager()
                .createNativeQuery(
                    "insert into ruleset_version_counter (ruleset, next_version) values (:ruleset,"
                        + " 2) on conflict (ruleset) do update set next_version ="
                        + " ruleset_version_counter.next_version + 1 returning next_version - 1",
                    Long.class)
                .setParameter("ruleset", ruleset)
                .getSingleResult())
        .longValue();
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
