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

// The single port every framework binding depends on, directly or via decision-engine-grpc. The
// only real implementation is DroolsRuleEvaluator in decision-engine-core - see
// IMPLEMENTATION-SPEC.md Section 7.3 for its full required behavior, most importantly: fail closed
// (throw RuleEvaluationException) rather than ever returning an outcome whose "outcome" key is
// null or absent.
public interface RuleEvaluator {
  EvaluationOutcome evaluate(EvaluationInput input);
}
