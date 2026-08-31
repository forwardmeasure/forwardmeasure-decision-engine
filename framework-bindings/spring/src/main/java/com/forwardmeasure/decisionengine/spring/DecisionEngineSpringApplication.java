/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

@SpringBootApplication
@EntityScan(
    basePackages = {
      "com.forwardmeasure.jpa.identity.entity",
      "com.forwardmeasure.jpa.locking.entity",
      "com.forwardmeasure.jpa.asynctask.entity",
      "com.forwardmeasure.decisionengine.jpa.entity"
    })
public class DecisionEngineSpringApplication {
  public static void main(String[] args) {
    SpringApplication.run(DecisionEngineSpringApplication.class, args);
  }
}
