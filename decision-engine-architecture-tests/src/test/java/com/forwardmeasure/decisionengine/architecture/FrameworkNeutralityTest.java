/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.forwardmeasure.decisionengine")
class FrameworkNeutralityTest {
  @ArchTest
  static final ArchRule domainAndCoreMustNotDependOnApplicationFrameworks =
      noClasses()
          .that()
          .resideInAnyPackage("..domain..", "..core..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.quarkus..", "org.springframework..", "io.micronaut..");
}
