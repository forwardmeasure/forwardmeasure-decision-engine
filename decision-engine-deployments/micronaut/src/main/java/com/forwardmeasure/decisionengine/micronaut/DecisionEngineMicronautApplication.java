/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.micronaut;

import io.micronaut.runtime.Micronaut;

public final class DecisionEngineMicronautApplication {
  private DecisionEngineMicronautApplication() {}

  public static void main(String[] args) {
    Micronaut.run(DecisionEngineMicronautApplication.class, args);
  }
}
