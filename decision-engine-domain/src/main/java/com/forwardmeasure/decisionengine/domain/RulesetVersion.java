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

import java.time.Instant;

// Plain domain representation of one ruleset version - deliberately not a reuse of the generated
// contract.v1.RulesetVersion (same reasoning as RulesetMode). decision-engine-jpa's
// RulesetVersionEntity / RulesetVersionMapper is the only place this is produced from persisted
// state; decision-engine-grpc's RulesetManagementServiceImpl is the only place it is converted to
// the wire type. See IMPLEMENTATION-SPEC.md Section 6.2 for the field-level rationale (identical
// field set to the CreateRulesetVersionRequest/RulesetVersion proto messages).
//
// maxWindowSize/idleTimeoutSeconds are meaningful only when mode == STATEFUL; both are required
// (validated > 0 at creation - Section 6.2) for a STATEFUL version and ignored for STATELESS ones.
public record RulesetVersion(
    String ruleset,
    long version,
    String drl,
    boolean active,
    RulesetMode mode,
    int maxWindowSize,
    int idleTimeoutSeconds,
    Instant createdAt,
    String createdBy) {}
