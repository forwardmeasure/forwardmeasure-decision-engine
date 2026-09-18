/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.grpc.tenancy;

/**
 * Thrown by a {@link TenantIdResolver} when a call cannot be attributed to a tenant - missing
 * metadata, an unparsable tenant id, an invalid/expired JWT, a JWT with no organization claim.
 * {@link TenantContextServerInterceptor} maps this to {@code Status.UNAUTHENTICATED}, never lets
 * the call reach a service method without a resolved tenant.
 */
public final class TenantResolutionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public TenantResolutionException(String message) {
    super(message);
  }

  public TenantResolutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
