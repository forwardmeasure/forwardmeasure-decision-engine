/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file to You under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.forwardmeasure.decisionengine.core;

/** Immutable snapshot of evaluator activity since process start. */
public record RuntimeStatistics(
    long invocationCount,
    long successCount,
    long failureCount,
    long cacheHitCount,
    long cacheMissCount,
    long cacheEvictionCount,
    long totalEvaluationNanos,
    long totalCompilationNanos) {}
