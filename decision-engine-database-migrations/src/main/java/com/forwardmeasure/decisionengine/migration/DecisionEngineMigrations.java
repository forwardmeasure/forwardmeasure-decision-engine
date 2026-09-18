/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.migration;

/**
 * Names decision-engine's own Liquibase changelog resource. Real database-per-tenant provisioning
 * goes through {@code com.forwardmeasure.openworkflow.migration.OpenWorkflowTenantMigrator} (see
 * {@code RulesetMigrationsMain}), reused as a shared library the same way forwardmeasure-entity-
 * intelligence's own migration Job does, rather than a second, parallel tenant-provisioning
 * implementation. This class used to be {@code RulesetSchemaMigrator} and owned that provisioning
 * logic itself, back when decision-engine was single-tenant (one fixed {@code public} schema, no
 * {@code CREATE DATABASE}/{@code CREATE SCHEMA} step at all) - kept under a new name, trimmed to
 * just the changelog constant every migration-related test still needs to reference.
 */
public final class DecisionEngineMigrations {
  public static final String CHANGELOG = "db/changelog/decision-engine-master.xml";

  private DecisionEngineMigrations() {}
}
