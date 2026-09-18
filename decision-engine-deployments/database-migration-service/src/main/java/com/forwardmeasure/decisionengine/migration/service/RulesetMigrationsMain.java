/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.migration.service;

import com.forwardmeasure.decisionengine.migration.DecisionEngineMigrations;
import com.forwardmeasure.jpa.datasource.TenantDataSourceRegistry;
import com.forwardmeasure.jpa.datasource.TenantDataSourceTemplate;
import com.forwardmeasure.jpa.tenancy.FunctionalSchema;
import com.forwardmeasure.jpa.tenancy.TenantDatabase;
import com.forwardmeasure.openworkflow.migration.OpenWorkflowTenantMigrator;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

/**
 * Bounded Kubernetes migration-job entry point - database-per-tenant, schema-per-domain ({@code
 * decision_intelligence}), same real {@link OpenWorkflowTenantMigrator} reused as a library that
 * forwardmeasure-entity-intelligence's own migration Job already uses, not a second, parallel
 * tenant-provisioning implementation. See that class's own javadoc for why {@code
 * platformDataSource} connects to the platform's own small control-plane database, never any
 * tenant's own database.
 */
public final class RulesetMigrationsMain {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RulesetMigrationsMain.class);

  private RulesetMigrationsMain() {}

  public static void main(String[] arguments) {
    // Administrator credential - this process's own connection to the platform's own small
    // control-plane database, matching fowf's own OpenWorkflowMigrationsMain/fei's
    // EntityIntelligenceMigrationsMain exactly. Used to create each tenant's own database
    // (idempotent) and create/rotate decision-engine's own runtime role; never the role the
    // running gRPC server connects as.
    String url = required("DECISION_ENGINE_DATABASE_URL");
    String username = required("DECISION_ENGINE_DATABASE_USERNAME");
    String password = required("DECISION_ENGINE_DATABASE_PASSWORD");
    String runtimeUsername = required("DECISION_ENGINE_RUNTIME_DATABASE_USERNAME");
    String runtimePassword = required("DECISION_ENGINE_RUNTIME_DATABASE_PASSWORD");

    DataSource platformDataSource = new DriverManagerDataSource(url, username, password);
    TenantDataSourceRegistry tenantDataSources =
        new TenantDataSourceRegistry(
            new TenantDataSourceTemplate(databaseUrlPrefix(url), username, password));
    OpenWorkflowTenantMigrator migrator =
        new OpenWorkflowTenantMigrator(
            platformDataSource,
            tenantDataSources,
            runtimeUsername,
            DecisionEngineMigrations.CHANGELOG,
            FunctionalSchema.DECISION_INTELLIGENCE);
    LOG.info("Ensuring runtime role {} exists", runtimeUsername);
    migrator.ensureRuntimeRole(runtimePassword);

    // Same alias derivation as fowf's own OpenWorkflowMigrationsMain / fei's
    // EntityIntelligenceMigrationsMain - TenantDatabase is always derived from the tenant's alias,
    // never supplied directly, so it can never drift apart. Deliberately does NOT call
    // TenantRegistry.register(...) itself - one tenant->database registration serves every
    // product sharing that database, and fowf's own openworkflow-migrations Job already owns that
    // registration; decision-engine only needs TenantDatabase.forAlias(String) to resolve the same
    // tenant database deterministically, no registry lookup.
    Arrays.stream(required("DECISION_ENGINE_TENANTS").split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(value -> value.split(":", 2))
        .forEach(
            parts -> {
              if (parts.length != 2) {
                throw new IllegalArgumentException("Tenant entries must be alias:display-name");
              }
              TenantDatabase database = TenantDatabase.forAlias(parts[0]);
              LOG.info("Provisioning tenant {} -> database {}", parts[0], database.value());
              migrator.provisionAndMigrate(database);
              LOG.info("Provisioned tenant {} -> database {}", parts[0], database.value());
            });
    LOG.info("Migration complete");
  }

  /**
   * Derives the tenant-database-per-tenant JDBC URL prefix from {@code
   * DECISION_ENGINE_DATABASE_URL} (a complete URL to the platform database) by stripping its
   * trailing database-name segment.
   */
  static String databaseUrlPrefix(String url) {
    int lastSlash = url.lastIndexOf('/');
    if (lastSlash < 0) {
      throw new IllegalArgumentException(
          "DECISION_ENGINE_DATABASE_URL must be a JDBC URL with a database name: " + url);
    }
    return url.substring(0, lastSlash + 1);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value.trim();
  }

  private record DriverManagerDataSource(String url, String username, String password)
      implements DataSource {
    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }

    @Override
    public Connection getConnection(String user, String pass) throws SQLException {
      return DriverManager.getConnection(url, user, pass);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return DriverManager.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      DriverManager.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return DriverManager.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return false;
    }
  }
}
