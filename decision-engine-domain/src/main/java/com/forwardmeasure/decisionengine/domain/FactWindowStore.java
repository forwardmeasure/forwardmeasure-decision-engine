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

import java.util.List;
import java.util.Map;

// The only real implementation is ValkeyFactWindowStore in decision-engine-fact-window. See
// IMPLEMENTATION-SPEC.md Section 7.4 for the full design rationale (why Valkey, not Postgres; why
// not a long-lived in-process KieSession; the atomic Lua-script append-and-load and its
// concurrent-call ordering guarantee) and the fail-closed rule for this specific port: if the
// backing store is unreachable, throw rather than silently evaluating with an empty window - that
// would invisibly downgrade a STATEFUL ruleset's real behavior to stateless.
public interface FactWindowStore {

  // Appends `fact` to the window for (ruleset, rulesetVersion, sessionKey), enforcing the
  // ruleset's own configured bound (RulesetVersion.maxWindowSize / idleTimeoutSeconds), and
  // returns the full current window (oldest first) including the fact just appended - one round
  // trip (a single atomic server-side script - Section 7.4), not several.
  //
  // The window is scoped by rulesetVersion, not just ruleset+sessionKey: a session that spans an
  // active-version change (or moves between pinned versions) must never mix facts accumulated
  // under a DRL whose schema/rule assumptions may differ from the version now firing. Moving to a
  // new version for an in-progress session starts a fresh window by design - this is a
  // consequence of the key shape, not special-cased application logic.
  List<Map<String, Object>> appendAndLoad(
      String ruleset,
      long rulesetVersion,
      String sessionKey,
      Map<String, Object> fact,
      int maxWindowSize,
      int idleTimeoutSeconds);
}
