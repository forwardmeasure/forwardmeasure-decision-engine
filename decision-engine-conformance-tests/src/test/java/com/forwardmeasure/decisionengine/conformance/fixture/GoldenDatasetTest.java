/*
 * Copyright 2026 Forward Measure
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.forwardmeasure.decisionengine.conformance.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class GoldenDatasetTest {

  @Test
  void loadsStatelessGoldenDatasets() {
    List<GoldenDataset> datasets =
        List.of(GoldenDataset.load("payments-risk"), GoldenDataset.load("customer-segmentation"));

    assertEquals(
        List.of("golden/paymentsRisk", "golden/customerSegmentation"),
        datasets.stream().map(GoldenDataset::ruleset).toList());
    datasets.forEach(
        dataset -> {
          assertEquals("STATELESS", dataset.mode());
          assertFalse(dataset.drl().isBlank());
          assertEquals(2, dataset.cases().size());
          dataset.cases().forEach(testCase -> assertNotNull(testCase.outcome()));
        });
  }

  @Test
  void loadsStatefulGoldenDatasetWithExpectedThresholdFailures() {
    GoldenDataset dataset = GoldenDataset.load("transaction-velocity");

    assertEquals("golden/transactionVelocity", dataset.ruleset());
    assertEquals("STATEFUL", dataset.mode());
    assertEquals(10, dataset.maxWindowSize());
    assertEquals(300, dataset.idleTimeoutSeconds());
    assertEquals("FAILED_PRECONDITION", dataset.cases().get(0).expectedStatus());
    assertEquals("FAILED_PRECONDITION", dataset.cases().get(1).expectedStatus());
    assertEquals("velocity_alert", dataset.cases().get(2).outcome());
  }
}
