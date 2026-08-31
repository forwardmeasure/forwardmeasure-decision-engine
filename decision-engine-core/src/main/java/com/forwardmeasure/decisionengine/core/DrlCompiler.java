/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.core;

import java.util.Objects;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;

/** Compiles immutable DRL definitions into KIE containers. */
public final class DrlCompiler {

  private final KieServices kieServices;

  public DrlCompiler() {
    this(KieServices.get());
  }

  DrlCompiler(KieServices kieServices) {
    this.kieServices = Objects.requireNonNull(kieServices, "kieServices");
  }

  public KieContainer compile(String ruleset, long version, String drl) {
    Objects.requireNonNull(ruleset, "ruleset");
    Objects.requireNonNull(drl, "drl");

    ReleaseId releaseId =
        kieServices.newReleaseId(
            "com.forwardmeasure.decisionengine",
            "ruleset-" + Integer.toUnsignedString(Objects.hash(ruleset, version)),
            "1.0.0");
    KieFileSystem fileSystem =
        kieServices
            .newKieFileSystem()
            .generateAndWritePomXML(releaseId)
            .write("src/main/resources/ruleset.drl", drl);
    KieBuilder builder = kieServices.newKieBuilder(fileSystem).buildAll();
    if (builder.getResults().hasMessages(Message.Level.ERROR)) {
      String details =
          builder.getResults().getMessages(Message.Level.ERROR).stream()
              .map(Message::getText)
              .reduce((left, right) -> left + "; " + right)
              .orElse("unknown compilation error");
      throw new DrlCompilationException(ruleset, version, details);
    }
    return kieServices.newKieContainer(releaseId);
  }
}
