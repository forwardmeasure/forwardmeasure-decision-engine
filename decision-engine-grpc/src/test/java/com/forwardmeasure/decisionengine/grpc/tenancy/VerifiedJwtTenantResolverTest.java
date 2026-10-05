/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.forwardmeasure.authzen.testkit.AuthzenKeycloakFixture;
import com.forwardmeasure.jpa.tenancy.TenantId;
import io.grpc.Metadata;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Real, end-to-end coverage of {@link VerifiedJwtTenantResolver} against a real running Keycloak
 * (via {@link AuthzenKeycloakFixture} - real Organizations-shaped signed RS256 JWTs, real JWKS
 * endpoint, real Admin REST provisioning) - not a hand-built claims map or a stubbed JWKS source.
 * This class's own responsibility is JWT signature/issuer/expiry verification and the
 * missing-or-malformed-header cases; {@code KeycloakOrganizationClaimsTest}
 * (forwardmeasure-authzen) already covers the claims-shape edge cases exhaustively once a token's
 * signature is trusted, so this test doesn't re-derive those - it proves the two layers are wired
 * together correctly end to end (a real token this resolver verifies really does resolve to the
 * right tenant).
 */
class VerifiedJwtTenantResolverTest {
  private static final Metadata.Key<String> AUTHORIZATION_HEADER =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final String ORGANIZATION_ALIAS = "acme-decision-engine";
  private static final com.forwardmeasure.jpa.tenancy.Did TENANT_DID =
      com.forwardmeasure.jpa.tenancy.Did.parse("did:fwmtest:tenant:" + UUID.randomUUID());
  private static final UUID TENANT_UUID =
      com.forwardmeasure.jpa.tenancy.TenantId.forDid(TENANT_DID).value();
  private static final String ROLE = "decision-engine-caller";

  private static AuthzenKeycloakFixture KEYCLOAK;
  private static String TOKEN;

  @BeforeAll
  static void startFixture() {
    KEYCLOAK = AuthzenKeycloakFixture.start();
    KEYCLOAK.provisionTenant(ORGANIZATION_ALIAS, TENANT_DID, ROLE);
    TOKEN = KEYCLOAK.mintUserToken();
  }

  @AfterAll
  static void stopFixture() {
    KEYCLOAK.close();
  }

  @Test
  void resolvesTheTenantFromARealSignedJwt() {
    VerifiedJwtTenantResolver resolver = resolver();
    TenantId resolved = resolver.resolve(headers("Bearer " + TOKEN));
    assertEquals(TenantId.parse(TENANT_UUID.toString()), resolved);
  }

  @Test
  void rejectsMissingAuthorizationMetadata() {
    VerifiedJwtTenantResolver resolver = resolver();
    assertThrows(TenantResolutionException.class, () -> resolver.resolve(new Metadata()));
  }

  @Test
  void rejectsAnAuthorizationHeaderWithoutTheBearerPrefix() {
    VerifiedJwtTenantResolver resolver = resolver();
    assertThrows(TenantResolutionException.class, () -> resolver.resolve(headers(TOKEN)));
  }

  @Test
  void rejectsATokenThatIsNotValidJws() {
    VerifiedJwtTenantResolver resolver = resolver();
    assertThrows(
        TenantResolutionException.class, () -> resolver.resolve(headers("Bearer not-a-real-jwt")));
  }

  @Test
  void rejectsATokenSignedForADifferentExpectedIssuer() {
    VerifiedJwtTenantResolver resolver =
        new VerifiedJwtTenantResolver(
            jwksUri(), "http://issuer.example.test/realms/some-other-realm", "unused-client-id");
    assertThrows(
        TenantResolutionException.class, () -> resolver.resolve(headers("Bearer " + TOKEN)));
  }

  @Test
  void rejectsATokenWhoseOrganizationRolesLiveOnADifferentClient() {
    // Same real, signature-valid token - only the client id VerifiedJwtTenantResolver reads
    // nested resource_access roles under changes, mirroring KeycloakOrganizationClaimsTest's own
    // "leaked top-level role must not authorize" precedent: a client this actor holds no
    // Organization-scoped roles on must not resolve, even with an otherwise-valid, correctly
    // issued token.
    VerifiedJwtTenantResolver resolver =
        new VerifiedJwtTenantResolver(
            jwksUri(), KEYCLOAK.issuer().toString(), "a-client-with-no-role-mapping");
    assertThrows(
        TenantResolutionException.class, () -> resolver.resolve(headers("Bearer " + TOKEN)));
  }

  private static VerifiedJwtTenantResolver resolver() {
    return new VerifiedJwtTenantResolver(
        jwksUri(), KEYCLOAK.issuer().toString(), AuthzenKeycloakFixture.CLIENT_ID);
  }

  private static String jwksUri() {
    return KEYCLOAK.issuer().toString() + "/protocol/openid-connect/certs";
  }

  private static Metadata headers(String authorizationValue) {
    Metadata metadata = new Metadata();
    metadata.put(AUTHORIZATION_HEADER, authorizationValue);
    return metadata;
  }
}
