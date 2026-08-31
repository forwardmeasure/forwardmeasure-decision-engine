/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at https://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 */
package com.forwardmeasure.decisionengine.domain;

// The port DroolsRuleEvaluator (decision-engine-core) uses to resolve which DRL/mode/window
// bounds to evaluate against, given a ruleset name and an optional pinned version
// (EvaluationInput.pinnedVersion). The only real implementation is a thin adapter in
// decision-engine-jpa over RulesetVersionService/RulesetVersionRepository - core never talks to
// JPA/Postgres directly (IMPLEMENTATION-SPEC.md Section 4's hexagonal-boundary rule).
//
// Both methods throw RulesetNotFoundException, never return null, when no matching version
// exists - this is what makes "ruleset not found" and "ruleset found but rules didn't produce an
// outcome" two distinct, distinguishable failures upstream (Section 7.2).
public interface RulesetSource {

  RulesetVersion getActiveVersion(String ruleset);

  RulesetVersion getVersion(String ruleset, long version);
}
