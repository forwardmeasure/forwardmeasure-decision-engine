/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.jpa.application;

import com.forwardmeasure.decisionengine.domain.RulesetMode;
import com.forwardmeasure.decisionengine.domain.RulesetVersion;
import java.util.List;

public interface RulesetVersionService {
  RulesetVersion create(
      String ruleset,
      String drl,
      RulesetMode mode,
      int maxWindowSize,
      int idleTimeoutSeconds,
      String createdBy,
      boolean activate);

  RulesetVersion getActive(String ruleset);

  RulesetVersion get(String ruleset, long version);

  List<RulesetVersion> list(String ruleset, String cursor, int limit);

  RulesetVersion activate(String ruleset, long version);

  boolean delete(String ruleset, long version);
}
