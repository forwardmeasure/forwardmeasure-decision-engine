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
 * Resolves the tenant a gRPC call belongs to. Two real implementations, chosen per deployment by
 * whichever trust boundary that deployment actually has - see {@link
 * TrustingMetadataTenantResolver} (internal-only deployments, caller already resolved tenant
 * identity upstream) and {@link VerifiedJwtTenantResolver} (standalone deployments, reachable by
 * callers outside a boundary decision-engine controls itself).
 */
@FunctionalInterface
public interface TenantIdResolver {
  /**
   * @throws TenantResolutionException if the call cannot be attributed to a tenant
   */
  TenantId resolve(Metadata headers);
}
