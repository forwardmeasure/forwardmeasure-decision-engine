/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.forwardmeasure.jpa.datasource.TenantDataSourceRegistry;
import com.forwardmeasure.jpa.datasource.TenantDataSourceTemplate;
import com.forwardmeasure.jpa.tenancy.FunctionalSchema;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import com.forwardmeasure.openworkflow.migration.OpenWorkflowTenantMigrator;
import com.forwardmeasure.testcontainers.junit.postgresql.WithPostgreSqlContainer;
import com.forwardmeasure.testcontainers.postgresql.PostgreSqlTestContainer;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@WithPostgreSqlContainer(databaseName = "decision_engine_migrations")
class DecisionEngineTenantMigrationTest {
  private static final String SCHEMA = FunctionalSchema.DECISION_INTELLIGENCE.schemaName();

  @Test
  void migratesRulesetSchemaIntoItsOwnTenantDatabaseAndCreatesActiveVersionBackstop(
      PostgreSqlTestContainer database) throws Exception {
    TenantDatabase tenantDatabase = TenantDatabase.forAlias("tenantmigrationtest");
    TenantDataSourceRegistry tenantDataSources =
        new TenantDataSourceRegistry(
            new TenantDataSourceTemplate(
                "jdbc:postgresql://" + database.host() + ":" + database.mappedPort() + "/",
                database.username(),
                database.password()));
    OpenWorkflowTenantMigrator migrator =
        new OpenWorkflowTenantMigrator(
            database.dataSource(),
            tenantDataSources,
            "decision_engine_runtime",
            DecisionEngineMigrations.CHANGELOG,
            FunctionalSchema.DECISION_INTELLIGENCE);
    migrator.ensureRuntimeRole(database.password());
    migrator.provisionAndMigrate(tenantDatabase);

    DataSource tenant = tenantDataSources.dataSourceFor(tenantDatabase);
    try (Connection connection = tenant.getConnection();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "select count(*) from information_schema.tables where table_schema='"
                    + SCHEMA
                    + "' and table_name='ruleset_version'")) {
      rows.next();
      assertEquals(1, rows.getInt(1));
    }
    try (Connection connection = tenant.getConnection();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "select count(*) from pg_indexes where schemaname='"
                    + SCHEMA
                    + "' and indexname='uq_ruleset_version_active'")) {
      rows.next();
      assertEquals(1, rows.getInt(1));
    }
  }
}
