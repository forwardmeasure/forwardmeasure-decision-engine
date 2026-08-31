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

// Every cause is fail-closed (IMPLEMENTATION-SPEC.md Sections 7.2/7.4 - never treat any of these
// as an implicit permit or an implicit anything). `reason` exists so decision-engine-grpc's
// EvaluationServiceImpl can map each cause to the correct gRPC status by switching on an enum,
// never by string-matching getMessage() - see IMPLEMENTATION-SPEC.md Section 7.2 for the intended
// status mapping.
public final class RuleEvaluationException extends RuntimeException {

  public enum Reason {
    // STATEFUL ruleset called with a null/blank session_key. -> INVALID_ARGUMENT
    INVALID_SESSION_KEY,
    // Rules fired but never set result.get("outcome"), or set it to null. -> FAILED_PRECONDITION
    MISSING_OUTCOME,
    // FactWindowStore was unreachable for a STATEFUL ruleset - never silently fall back to an
    // empty window (Section 7.4). -> UNAVAILABLE
    FACT_WINDOW_UNAVAILABLE,
    // The KieSession threw during firing (a Drools-level runtime failure, not a validation
    // failure - DRL that fails to compile is rejected at CreateRulesetVersion time, Section 6.2,
    // and never reaches evaluation as this reason). -> INTERNAL
    EVALUATION_FAILURE
  }

  private final String ruleset;
  private final long version;
  private final Reason reason;

  public RuleEvaluationException(String ruleset, long version, Reason reason, String message) {
    this(ruleset, version, reason, message, null);
  }

  public RuleEvaluationException(
      String ruleset, long version, Reason reason, String message, Throwable cause) {
    super("ruleset " + ruleset + " version " + version + " [" + reason + "]: " + message, cause);
    this.ruleset = ruleset;
    this.version = version;
    this.reason = reason;
  }

  public String ruleset() {
    return ruleset;
  }

  public long version() {
    return version;
  }

  public Reason reason() {
    return reason;
  }
}
