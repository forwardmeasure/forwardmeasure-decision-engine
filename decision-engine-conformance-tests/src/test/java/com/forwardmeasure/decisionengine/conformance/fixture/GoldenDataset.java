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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A golden ruleset and its deterministic evaluation cases. */
public record GoldenDataset(
    String ruleset,
    String mode,
    String drl,
    int maxWindowSize,
    int idleTimeoutSeconds,
    List<GoldenCase> cases) {

  private static final Gson GSON = new Gson();
  private static final TypeToken<CaseDocument> DOCUMENT_TYPE = new TypeToken<>() {};

  public static GoldenDataset load(String name) {
    String base = "golden/" + name + "/";
    CaseDocument document;
    try (InputStream stream = resource(base + "cases.json")) {
      try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        document = GSON.fromJson(reader, DOCUMENT_TYPE.getType());
      }
    } catch (IOException exception) {
      throw new IllegalStateException("could not read golden cases for " + name, exception);
    }
    try (InputStream stream = resource(base + "ruleset.drl")) {
      String drl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      return new GoldenDataset(
          document.ruleset,
          document.mode,
          drl,
          document.maxWindowSize,
          document.idleTimeoutSeconds,
          List.copyOf(document.cases));
    } catch (IOException exception) {
      throw new IllegalStateException("could not read golden ruleset for " + name, exception);
    }
  }

  private static InputStream resource(String path) {
    return Objects.requireNonNull(
        GoldenDataset.class.getClassLoader().getResourceAsStream(path),
        "missing golden dataset resource: " + path);
  }

  private record CaseDocument(
      String ruleset,
      String mode,
      int maxWindowSize,
      int idleTimeoutSeconds,
      List<GoldenCase> cases) {}

  public record GoldenCase(
      String name,
      Map<String, Object> input,
      String outcome,
      List<String> firedRules,
      int factsConsidered,
      String expectedStatus) {
    public GoldenCase {
      input = input == null ? Map.of() : Map.copyOf(input);
      firedRules = firedRules == null ? List.of() : List.copyOf(firedRules);
    }
  }
}
