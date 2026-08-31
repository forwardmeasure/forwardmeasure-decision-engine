/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.micronaut;

import io.micronaut.core.annotation.Introspected;
import jakarta.persistence.Entity;

/** Generates Micronaut's compile-time metadata for the decision-engine JPA entity. */
@Introspected(
    packages = "com.forwardmeasure.decisionengine.jpa.entity",
    includedAnnotations = Entity.class)
final class DecisionEngineEntityIntrospection {
  private DecisionEngineEntityIntrospection() {}
}
