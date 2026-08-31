/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.jpa.service;

import com.forwardmeasure.decisionengine.domain.RulesetSource;
import com.forwardmeasure.decisionengine.domain.RulesetVersion;
import com.forwardmeasure.decisionengine.jpa.application.RulesetVersionService;

public final class JpaRulesetSource implements RulesetSource {
  private final RulesetVersionService service;

  public JpaRulesetSource(RulesetVersionService service) {
    this.service = service;
  }

  @Override
  public RulesetVersion getActiveVersion(String ruleset) {
    return service.getActive(ruleset);
  }

  @Override
  public RulesetVersion getVersion(String ruleset, long version) {
    return service.get(ruleset, version);
  }
}
