/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc.tenancy;

import com.forwardmeasure.jpa.tenancy.TenantId;
import io.grpc.Metadata;

/**
 * Trusts a plain, unauthenticated tenant-id field the caller supplies in gRPC metadata - no JWT, no
 * signature verification, no Keycloak call. Correct only when decision-engine's real trust boundary
 * is "which pods can reach this port" (today: ClusterIP-only, ruleset_management/ evaluation
 * reachable exclusively from inside the cluster) and the caller has already resolved real tenant
 * identity upstream through its own verified flow before ever calling decision-engine - exactly
 * forwardmeasure-agent-os's situation today (it resolves tenant identity via fowf's own
 * Keycloak/AuthZEN organization flow before calling decision-engine's gRPC API at all).
 *
 * <p>Do not use this for a standalone deployment reachable by callers outside that boundary - see
 * {@link VerifiedJwtTenantResolver} instead.
 */
public final class TrustingMetadataTenantResolver implements TenantIdResolver {
  public static final Metadata.Key<String> TENANT_ID_HEADER =
      Metadata.Key.of("tenant-id", Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public TenantId resolve(Metadata headers) {
    String value = headers.get(TENANT_ID_HEADER);
    if (value == null || value.isBlank()) {
      throw new TenantResolutionException("Missing '" + TENANT_ID_HEADER.name() + "' metadata");
    }
    try {
      return TenantId.parse(value.trim());
    } catch (RuntimeException exception) {
      throw new TenantResolutionException("Invalid tenant id: " + value, exception);
    }
  }
}
