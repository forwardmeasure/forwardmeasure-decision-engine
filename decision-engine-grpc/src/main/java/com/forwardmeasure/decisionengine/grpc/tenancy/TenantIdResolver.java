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

/** Resolves call identity. Production bindings require signed organization claims. */
@FunctionalInterface
public interface TenantIdResolver {
  /**
   * @throws TenantResolutionException if the call cannot be attributed to a tenant
   */
  TenantId resolve(Metadata headers);

  /** A default for legacy fixtures; production resolvers must supply the verified identity. */
  default com.forwardmeasure.authzen.ActiveOrganization resolveOrganization(Metadata headers) {
    return null;
  }
}
