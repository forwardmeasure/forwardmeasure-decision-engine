/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.jpa.entity;

import com.forwardmeasure.decisionengine.domain.RulesetMode;
import com.forwardmeasure.jpa.core.entity.AbstractBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
    name = "ruleset_version",
    uniqueConstraints = @UniqueConstraint(columnNames = {"ruleset", "ruleset_version"}))
@Getter
@Setter
public class RulesetVersionEntity extends AbstractBaseEntity<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String ruleset;

  @Column(name = "ruleset_version", nullable = false)
  private long rulesetVersion;

  @Column(columnDefinition = "text", nullable = false)
  private String drl;

  @Column(nullable = false)
  private boolean active;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private RulesetMode mode;

  @Column(name = "max_window_size", nullable = false)
  private int maxWindowSize;

  @Column(name = "idle_timeout_seconds", nullable = false)
  private int idleTimeoutSeconds;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "created_by")
  private String createdBy;
}
