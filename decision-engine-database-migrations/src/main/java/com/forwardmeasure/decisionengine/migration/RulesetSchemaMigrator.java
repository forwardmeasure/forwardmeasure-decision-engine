/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.migration;

import com.forwardmeasure.database.migration.api.MigrationResult;
import com.forwardmeasure.jpa.liquibase.TenantSchemaMigrator;
import com.forwardmeasure.jpa.tenancy.TenantSchema;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** Runs the decision-engine schema migration using the administrator connection. */
public final class RulesetSchemaMigrator {
  public static final String CHANGELOG = "db/changelog/decision-engine-master.xml";
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,62}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final DataSource dataSource;
  private final String runtimeUsername;
  private final TenantSchemaMigrator liquibase;

  public RulesetSchemaMigrator(DataSource dataSource, String runtimeUsername) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.runtimeUsername = safeIdentifier(runtimeUsername);
    this.liquibase =
        new TenantSchemaMigrator(
            dataSource, CHANGELOG, Thread.currentThread().getContextClassLoader());
  }

  public void ensureRuntimeRole(String password) {
    Objects.requireNonNull(password, "password");
    String role = quote(runtimeUsername);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '"
              + runtimeUsername
              + "') THEN CREATE ROLE "
              + role
              + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; END IF; END $$");
      statement.execute("ALTER ROLE " + role + " PASSWORD " + dollarQuote(password));
      statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
    } catch (SQLException exception) {
      throw new IllegalStateException("could not provision runtime database role", exception);
    }
  }

  public MigrationResult provisionAndMigrate() {
    MigrationResult result = liquibase.migrate(TenantSchema.PUBLIC);
    grantRuntimeAccess();
    return result;
  }

  private void grantRuntimeAccess() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
              + quote(runtimeUsername));
      statement.execute(
          "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + quote(runtimeUsername));
    } catch (SQLException exception) {
      throw new IllegalStateException("could not grant runtime database access", exception);
    }
  }

  private static String safeIdentifier(String value) {
    Objects.requireNonNull(value, "runtimeUsername");
    if (!SAFE_IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException("runtimeUsername must be a safe SQL identifier");
    }
    return value;
  }

  private static String quote(String identifier) {
    return "\"" + identifier + "\"";
  }

  private static String dollarQuote(String value) {
    String tag;
    do {
      tag = "pw" + Long.toHexString(RANDOM.nextLong());
    } while (value.contains("$" + tag + "$"));
    return "$" + tag + "$" + value + "$" + tag + "$";
  }
}
