/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class RulesetSchemaMigratorIntegrationTest {
  @Test
  void migratesRulesetSchemaAndCreatesActiveVersionBackstop() throws Exception {
    try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
      postgres.start();
      var dataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      RulesetSchemaMigrator migrator =
          new RulesetSchemaMigrator(dataSource, "decision_engine_runtime");
      migrator.ensureRuntimeRole("password");
      migrator.provisionAndMigrate();
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "select count(*) from information_schema.tables where table_schema='public' and"
                      + " table_name='ruleset_version'")) {
        rows.next();
        assertEquals(1, rows.getInt(1));
      }
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "select count(*) from pg_indexes where schemaname='public' and"
                      + " indexname='uq_ruleset_version_active'")) {
        rows.next();
        assertEquals(1, rows.getInt(1));
      }
    }
  }

  private record DriverManagerDataSource(String url, String username, String password)
      implements javax.sql.DataSource {
    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
      return DriverManager.getConnection(url, username, password);
    }

    @Override
    public java.sql.Connection getConnection(String user, String pass)
        throws java.sql.SQLException {
      return DriverManager.getConnection(url, user, pass);
    }

    @Override
    public java.io.PrintWriter getLogWriter() throws java.sql.SQLException {
      return DriverManager.getLogWriter();
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) throws java.sql.SQLException {
      DriverManager.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws java.sql.SQLException {
      DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws java.sql.SQLException {
      return DriverManager.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger()
        throws java.sql.SQLFeatureNotSupportedException {
      throw new java.sql.SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws java.sql.SQLException {
      throw new java.sql.SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return false;
    }
  }
}
