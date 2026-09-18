/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc.tenancy;

import com.forwardmeasure.authzen.KeycloakOrganizationClaims;
import com.forwardmeasure.jpa.tenancy.TenantId;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.grpc.Metadata;
import java.net.URL;
import java.text.ParseException;
import java.util.Set;

/**
 * Verifies a Keycloak-issued JWT (RS256, fetched from the realm's own JWKS endpoint, standard
 * nimbus-jose-jwt {@code RemoteJWKSet}/{@code DefaultJWTProcessor} verification - signature,
 * expiry, issuer) pulled from the gRPC {@code authorization} metadata key, then extracts the
 * organization/tenant claim via {@link KeycloakOrganizationClaims#extract(java.util.Map, String)} -
 * the same framework-neutral claims-extraction fowf/fei's own {@code ActiveOrganizationProvider}
 * implementations use on the HTTP side, here fed from a call this class verified itself (gRPC has
 * no built-in JWT-verifying filter chain the way JAX-RS/Micronaut Security/Spring Security do on
 * the HTTP side - see this class's own construction site for why).
 *
 * <p>Use this only when decision-engine is reachable by callers outside a trust boundary it
 * controls itself (a standalone deployment) - for the current internal-only deployment, prefer
 * {@link TrustingMetadataTenantResolver}, which needs no token issuance/verification at all.
 */
public final class VerifiedJwtTenantResolver implements TenantIdResolver {
  private static final Metadata.Key<String> AUTHORIZATION_HEADER =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final String BEARER_PREFIX = "Bearer ";

  private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;
  private final String organizationClientId;

  /**
   * @param jwksUri the Keycloak realm's JWKS endpoint, e.g. {@code
   *     https://keycloak.example.com/realms/forwardmeasure/protocol/openid-connect/certs}
   * @param expectedIssuer the exact {@code iss} claim value Keycloak issues for that realm
   * @param organizationClientId the client id {@link KeycloakOrganizationClaims#extract} reads
   *     nested {@code resource_access} roles under
   */
  public VerifiedJwtTenantResolver(
      String jwksUri, String expectedIssuer, String organizationClientId) {
    this(
        jwksUri,
        expectedIssuer,
        organizationClientId,
        new DefaultResourceRetriever(
            /* connectTimeoutMs= */ 5000, /* readTimeoutMs= */ 5000, /* sizeLimitBytes= */ 51200));
  }

  VerifiedJwtTenantResolver(
      String jwksUri,
      String expectedIssuer,
      String organizationClientId,
      ResourceRetriever jwksRetriever) {
    this.organizationClientId =
        java.util.Objects.requireNonNull(organizationClientId, "organizationClientId");
    java.util.Objects.requireNonNull(expectedIssuer, "expectedIssuer");
    try {
      JWKSource<SecurityContext> jwkSource =
          new com.nimbusds.jose.jwk.source.RemoteJWKSet<>(new URL(jwksUri), jwksRetriever);
      JWSVerificationKeySelector<SecurityContext> keySelector =
          new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource);
      DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
      processor.setJWSKeySelector(keySelector);
      processor.setJWTClaimsSetVerifier(
          new DefaultJWTClaimsVerifier<>(
              new JWTClaimsSet.Builder().issuer(expectedIssuer).build(),
              Set.of("sub", "exp", "iss")));
      this.jwtProcessor = processor;
    } catch (java.net.MalformedURLException exception) {
      throw new IllegalArgumentException("Invalid jwksUri: " + jwksUri, exception);
    }
  }

  @Override
  public TenantId resolve(Metadata headers) {
    String authorization = headers.get(AUTHORIZATION_HEADER);
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      throw new TenantResolutionException("Missing or malformed 'authorization' metadata");
    }
    String token = authorization.substring(BEARER_PREFIX.length()).trim();
    JWTClaimsSet claims;
    try {
      claims = jwtProcessor.process(token, null);
    } catch (ParseException | com.nimbusds.jose.JOSEException exception) {
      throw new TenantResolutionException("JWT verification failed", exception);
    } catch (com.nimbusds.jose.proc.BadJOSEException exception) {
      throw new TenantResolutionException("JWT rejected: " + exception.getMessage(), exception);
    }
    try {
      return KeycloakOrganizationClaims.extract(claims.getClaims(), organizationClientId)
          .tenantId();
    } catch (RuntimeException exception) {
      throw new TenantResolutionException("Could not resolve tenant from JWT claims", exception);
    }
  }
}
