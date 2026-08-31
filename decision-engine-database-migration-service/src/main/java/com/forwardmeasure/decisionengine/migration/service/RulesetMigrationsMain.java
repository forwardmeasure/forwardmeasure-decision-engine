/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.migration.service;

import com.forwardmeasure.decisionengine.migration.RulesetSchemaMigrator;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Bounded migration-job entry point. */
public final class RulesetMigrationsMain {
  private RulesetMigrationsMain() {}

  public static void main(String[] args) {
    RulesetSchemaMigrator migrator =
        new RulesetSchemaMigrator(
            new DriverManagerDataSource(
                required("DECISION_ENGINE_DATABASE_URL"),
                required("DECISION_ENGINE_DATABASE_USERNAME"),
                required("DECISION_ENGINE_DATABASE_PASSWORD")),
            required("DECISION_ENGINE_RUNTIME_DATABASE_USERNAME"));
    migrator.ensureRuntimeRole(required("DECISION_ENGINE_RUNTIME_DATABASE_PASSWORD"));
    migrator.provisionAndMigrate();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
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
