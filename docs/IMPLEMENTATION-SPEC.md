# forwardmeasure-decision-engine — Implementation Specification

**Audience: an autonomous coding agent (Codex) building this repository from scratch.**
**This document is prescriptive, not suggestive. Where it gives an exact name, version, package,
or file path, use that exact value. Where it says "do not," treat that as a hard constraint, not a
default you may override if you find a cleverer approach. Section 13 lists everything you must NOT
build. If anything in this document is ambiguous, prefer the narrowest, simplest interpretation
consistent with Section 13, and leave a `// TODO(spec-gap):` comment rather than inventing scope.**

---

## 1. Purpose and scope

Build a standalone, general-purpose rule-evaluation server. A caller sends a **ruleset name** plus
a **structured input document**; the server runs that ruleset's Drools rules against the input and
returns a structured decision. The server has no idea what any particular ruleset means — it is a
generic execution engine, not a policy service for any one product.

**Relationship to other repositories**: this is a **new, fully independent repository**,
`forwardmeasure-decision-engine`, a sibling of `forwardmeasure-agent-os`, not a module inside it and
not a dependency of it. `forwardmeasure-agent-os` currently calls a *different* policy engine (Open
Policy Agent, via `agent-os-policy-opa`) for its own policy gate. This repository does not know
that agent-os exists, and does not depend on `forwardmeasure-platform/deploy` or the shared
`helm-charts` repository for its deployment (Section 9) — it must build, test, and deploy itself with
nothing beyond this repository and the platform Maven parent it inherits from. The two engines (OPA
and this one) are being evaluated in parallel; this repository must stand on its own regardless of
the outcome of that comparison.

**Why gRPC, why Drools**: gRPC for a strongly-typed, low-overhead contract with native multi-language
codegen. Drools for its forward-chaining/CEP capability — reasoning over facts and rule
interactions *across a sequence of calls*, not just a single static input snapshot — which is the
capability this project exists to provide that simpler engines (OPA/Rego) structurally lack. Section
7.4 is the part of this document that actually delivers that capability; do not treat it as optional
polish — a build of this project that only supports single-call, stateless evaluation has no
capability advantage over OPA at all, and the entire reason for this project not existing as "just
another OPA policy" evaporates without it.

**The one architectural rule that matters most in this document**: the engine core must never
depend on any application framework. `KieServices` / `KieContainer` / `KieSession` calls live in one
framework-neutral module. Quarkus, Spring, and Micronaut modules exist **only** to expose that core
over gRPC — each does this via **its own framework's own gRPC extension** (not a Drools-specific
Quarkus/Spring/Micronaut integration; none of those are used anywhere in this project — see
Section 8 and Section 13).

---

## 2. Repository and module layout

```
forwardmeasure-decision-engine/
├── pom.xml                                    (parent, packaging=pom)
├── decision-engine-api-specifications/        (canonical, SINGLE-COPY .proto sources under
│                                                META-INF/proto/... - mirrors agent-os-api-
│                                                specifications' own META-INF/openapi convention in
│                                                forwardmeasure-agent-os exactly. A plain resources
│                                                jar; language-binding modules unpack it, never copy
│                                                the source files - see Section 6.3.)
├── decision-engine-api-language-bindings/     (generated bindings, split by language then by
│   ├── java/                                   PROTOCOL - grpc/ today, a thrift/ sibling can be
│   │   └── grpc/                               added under each language later without
│   ├── python/                                 restructuring anything, since a Thrift interface is
│   │   └── grpc/                               a planned future addition, not a hypothetical one)
│   └── typescript/
│       └── grpc/
├── decision-engine-domain/                    (ruleset value types, ports, exceptions - no I/O)
├── decision-engine-core/                      (KieServices/KieContainer/KieSession embedding - the
│                                                framework-neutral evaluator)
├── decision-engine-jpa/                       (ruleset DEFINITION persistence: entities,
│                                                repositories, ruleset management application
│                                                service - Postgres, Section 7.3)
├── decision-engine-fact-window/                (stateful-ruleset fact HISTORY persistence - Valkey,
│                                                Section 7.4 - independent of decision-engine-jpa;
│                                                a stateless-only deployment never needs this
│                                                module's adapter wired up at all)
├── decision-engine-grpc/                      (framework-neutral gRPC service implementation
│                                                classes - see Section 8.1)
├── decision-engine-database-migrations/       (Liquibase changelog + schema migrator for ruleset
│                                                DEFINITIONS only - mirrors the pattern in
│                                                Section 7.3)
├── decision-engine-database-migration-service/ (bounded Job entry point, mirrors Section 7.3)
├── framework-bindings/
│   ├── quarkus/
│   ├── spring/
│   └── micronaut/
├── decision-engine-architecture-tests/         (ArchUnit: core/domain must never depend on any
│                                                framework package - see Section 11.4)
├── decision-engine-conformance-tests/          (black-box gRPC client tests run against each of the
│                                                three framework bindings in turn - see Section 11.3)
└── deploy/                                     (this project's OWN, self-contained deployment -
    ├── helm/                                    Section 9. Not published through the shared
    │   └── decision-engine/                      helm-charts repo, not dependent on
    │       ├── Chart.yaml                         forwardmeasure-platform/deploy)
    │       ├── values.yaml
    │       └── templates/
    └── helmfile/
        ├── helmfile.yaml.gotmpl
        ├── environments/
        └── releases/
```

Maven `groupId` for every module: `com.forwardmeasure.decisionengine`. Parent `artifactId`:
`forwardmeasure-decision-engine-parent`. The parent POM's own `<parent>` is
`com.forwardmeasure.platform:forwardmeasure-platform:1.0.0` (relativePath
`../forwardmeasure-platform/pom.xml`) — the same parent every other forwardmeasure Java repository
uses. Do not invent a different parent chain. Do not skip having a parent — every dependency version
in this spec that has no explicit `<version>` is expected to come from that parent's own
dependency management (its BOM imports already pin `grpc-bom` 1.83.1 and `protobuf-java` /
`protobuf-java-util` 4.35.1 — confirmed present in
`forwardmeasure-platform/pom.xml` before this spec was written). Where this document names a
version explicitly (Drools, the framework versions in Section 8, Valkey client libraries), that
version is *not* yet in the platform BOM and must be declared locally in the relevant module.

Java version: **25** (`<maven.compiler.release>25</maven.compiler.release>`, inherited from the
platform parent — do not override).

---

## 3. Technology stack (exact versions)

| Concern | Choice | Version | Source |
|---|---|---|---|
| Rule engine | Apache KIE Drools (`org.drools` / `org.kie` group under Apache governance - verify current groupId against `https://kie.apache.org/components/drools/` before pinning) | `10.2.0` | Confirmed current stable as of this spec's writing |
| gRPC | `io.grpc:grpc-bom` | `1.83.1` | Already in `forwardmeasure-platform` BOM |
| Protobuf | `com.google.protobuf:protobuf-java`, `protobuf-java-util` | `4.35.1` | Already in `forwardmeasure-platform` BOM |
| Protobuf codegen | `org.xolstice.maven.plugins:protobuf-maven-plugin` plus `kr.motd.maven:os-maven-plugin` | `protobuf-maven-plugin` `0.6.1`, `os-maven-plugin` `1.7.1`; compiler and gRPC generator are Maven-resolved artifacts | No globally installed `protoc` is required |
| Quarkus | `quarkus-grpc` | whatever Quarkus platform version `forwardmeasure-agent-os`'s own `agent-os-execution-quarkus` currently uses (confirmed Quarkus 3.38.1 at spec-writing time) - inherit, do not repin independently | agent-os precedent |
| Spring | `spring-boot-grpc-server` | Spring Boot **4.1.x** (first-party gRPC support; do not use the third-party `net.devh`/`grpc-spring` starter - see Section 13) | Confirmed shipped in Spring Boot 4.1, June 2026 |
| Micronaut | `io.micronaut.grpc:micronaut-grpc-server-runtime` (verify exact artifact coordinates against `https://micronaut-projects.github.io/micronaut-grpc/snapshot/guide/index.html` at implementation time - do not guess) | Micronaut 5.x, latest `micronaut-grpc` compatible release | Confirmed actively maintained, last release July 2026 |
| Fact-definition persistence | `forwardmeasure-jpa` (core, tenancy, liquibase, spring/quarkus/micronaut adapters as needed) | `1.0.0` | Same as agent-os |
| Migrations | `forwardmeasure-database-migrations` | `1.0.0` | Same as agent-os |
| Test infra | `forwardmeasure-testcontainers` | `1.0.0` | Same as agent-os - confirm it has (or add, upstream, if it does not yet) a Valkey/Redis Testcontainers module for Section 11.2's fact-window tests |
| Stateful-ruleset fact window | Valkey, client via `io.lettuce:lettuce-core` | chart: match whichever version `forwardmeasure-platform`'s own `chartVersions.valkey` currently pins. Client: pin a current Lettuce release locally in `decision-engine-fact-window` (not yet in the platform BOM) | Section 7.4 - **plain Lettuce, not a per-framework Redis integration** (not `quarkus-redis-client`/`spring-boot-starter-data-redis`/`micronaut-redis-*`). `decision-engine-fact-window` is framework-neutral like `decision-engine-core` and `decision-engine-jpa` - Lettuce is the driver all three of those framework extensions wrap internally anyway, so using it directly here avoids three redundant per-framework integrations for one simple client. |
| Object mapping | MapStruct | version already managed by the platform parent | Use for entity↔domain mapping in `decision-engine-jpa` (Section 7.3) - never hand-write field-by-field mappers |
| Boilerplate reduction | Lombok | version already managed by the platform parent | Use on JPA entities and simple data holders only - never on domain ports/interfaces, never on anything with real behavior |
| Logging | SLF4J (`org.slf4j:slf4j-api`) | version already managed by the platform parent | Log through SLF4J only. Never `System.out`/`e.printStackTrace()`. Never a framework-specific logging facade directly in framework-neutral modules (`decision-engine-core`, `decision-engine-domain`, `decision-engine-grpc`, `decision-engine-fact-window`) |

**Do not repin any version this table marks "already in BOM" or "inherit."** If a build error suggests
a version conflict, fix the dependency declaration, not the pinned version.

---

## 4. High-level architecture

```
                    ┌───────────────────────────────────────────┐
                    │  decision-engine-api-specifications          │
                    │  + decision-engine-api-language-bindings     │
                    │  (evaluation.proto, ruleset_management.proto)│
                    └────────────────────┬──────────────────────┘
                                          │ generated types
        ┌──────────────────────────────────┼──────────────────────────────────┐
        │                                  │                                  │
┌───────▼────────┐              ┌──────────▼──────────┐                       │
│ decision-engine- │◄────────────│ decision-engine-core  │                       │
│    domain         │             │ (DroolsRuleEvaluator:  │                       │
│ (RuleEvaluator    │             │  KieServices/Container/│                       │
│  port, exceptions,│             │  Session embedding;    │                       │
│  value types -    │             │  stateful/stateless     │                       │
│  NO I/O)          │             │  branching, Section 7.4)│                       │
└──────────────────┘             └───┬────────────────┬───┘                       │
                                       │ ruleset         │ fact history             │
                                       │ definitions      │ (stateful rulesets only) │
                            ┌──────────▼─────────┐  ┌────▼──────────────────┐       │
                            │ decision-engine-jpa   │  │ decision-engine-fact-   │       │
                            │ (Ruleset entity,      │  │   window                │       │
                            │  RulesetRepository,   │  │ (Valkey-backed          │       │
                            │  RulesetService -     │  │  FactWindowStore -      │       │
                            │  Postgres)            │  │  bounded list + TTL     │       │
                            └──────────────────────┘  │  per session_key)       │       │
                                                        └────────────────────────┘       │
                                          ┌──────────────────────┐                       │
                                          │  decision-engine-grpc  │◄──────────────────────┘
                                          │ (EvaluationServiceImpl,│
                                          │  ManagementServiceImpl │
                                          │  - framework-neutral)  │
                                          └──────────┬───────────┘
                                                    │
                          ┌─────────────────────────┼─────────────────────────┐
                          │                         │                         │
                ┌─────────▼─────────┐    ┌──────────▼──────────┐    ┌─────────▼─────────┐
                │  framework-bindings │    │  framework-bindings   │    │ framework-bindings │
                │       /quarkus       │    │       /spring          │    │     /micronaut      │
                │ (quarkus-grpc wiring │    │ (spring-boot-grpc-     │    │ (micronaut-grpc     │
                │  ONLY)               │    │  server wiring ONLY)   │    │  wiring ONLY)       │
                └─────────────────────┘    └───────────────────────┘    └───────────────────┘
```

This is the same hexagonal shape used throughout `forwardmeasure-agent-os` (domain port +
application core, framework-neutral gRPC layer, three thin framework bindings), with one addition
agent-os doesn't have: a *second* persistence-adjacent port (`FactWindowStore`, backed by Valkey,
not Postgres) alongside the usual JPA one — because this project has two genuinely different kinds
of state (Section 7.3 vs 7.4), not one.

---

## 5. Two capabilities, two `.proto` files

1. **Evaluation** (`EvaluationService`) — the hot path. Given a ruleset name and an input document,
   fire that ruleset's rules, return the decision. For a *stateful* ruleset, also reads/writes the
   fact window (Section 7.4).
2. **Management** (`RulesetManagementService`) — CRUD for ruleset definitions: create a new version,
   list versions, get the active version, activate/deactivate, delete. This is what makes the
   engine "general-purpose" rather than a fixed, build-time-baked rule set.

Both are gRPC, not REST. Do not add a REST/JAX-RS surface anywhere in this project (Section 13).

---

## 6. The gRPC contract

### 6.1 `evaluation.proto`

Location: `decision-engine-api-specifications/src/main/resources/META-INF/proto/forwardmeasure/decisionengine/v1/evaluation.proto` — the single canonical copy (Section 6.3).

```protobuf
syntax = "proto3";

package forwardmeasure.decisionengine.v1;

option java_package = "com.forwardmeasure.decisionengine.contract.v1";
option java_multiple_files = true;

import "google/protobuf/struct.proto";

service EvaluationService {
  rpc Evaluate(EvaluationRequest) returns (EvaluationResponse);
}

message EvaluationRequest {
  // Namespaced ruleset identifier, e.g. "agentos/execution/actionGate" - deliberately the same
  // hierarchical-string addressing convention as OPA's own policyPath in the parallel
  // forwardmeasure-agent-os investigation, so the two engines are drop-in comparable from a
  // caller's perspective. Validated server-side against the same pattern used for policyPath in
  // agent-policy-management.openapi.yaml: ^[a-z][a-zA-Z0-9]*(/[a-z][a-zA-Z0-9]*)*$
  string ruleset = 1;

  // Arbitrary structured input, converted to a generic fact for rule matching - see Section 7.2.
  // There is no per-ruleset Java type here on purpose: a new ruleset must never require a Java
  // code change in this project, only new DRL and a management-API call to publish it.
  google.protobuf.Struct input = 2;

  // Optional. If absent, the active version of the named ruleset is used. If present, pins
  // evaluation to that exact version - callers that need reproducible replay (e.g. re-running a
  // past decision for audit) supply this explicitly.
  optional int64 ruleset_version = 3;

  // REQUIRED for stateful rulesets (Section 7.4), ignored for stateless ones. Identifies which
  // fact-history stream this call belongs to - e.g. an agent execution ID, if the caller is
  // forwardmeasure-agent-os. The server does not interpret this string; it is purely a partition
  // key for the fact window. Calling a stateful ruleset without one is a client error
  // (INVALID_ARGUMENT), not silently treated as "no history".
  string session_key = 4;

  string correlation_id = 5;
}

message EvaluationResponse {
  // The ruleset's own `result` global, serialized back to a Struct verbatim - this project does
  // not impose any schema on what a ruleset returns beyond the fail-closed requirement in Section
  // 7.2 (it MUST contain a non-null "outcome" key or the call fails).
  google.protobuf.Struct result = 1;

  // Observability: names of every rule that fired during this evaluation, in firing order. Never
  // omit this - it is the only debugging signal available for "why did this ruleset decide X".
  repeated string fired_rules = 2;

  int64 ruleset_version = 3;

  // How many facts (including the current call's input) were in working memory when rules fired -
  // 1 for a stateless ruleset or a stateful ruleset's first call for a given session_key, growing
  // as the fact window accumulates. A direct, cheap observability signal for whether history is
  // actually being picked up.
  int32 facts_considered = 4;

  string correlation_id = 5;
}
```

### 6.2 `ruleset_management.proto`

Location: `decision-engine-api-specifications/src/main/resources/META-INF/proto/forwardmeasure/decisionengine/v1/ruleset_management.proto` — the single canonical copy (Section 6.3).

```protobuf
syntax = "proto3";

package forwardmeasure.decisionengine.v1;

option java_package = "com.forwardmeasure.decisionengine.contract.v1";
option java_multiple_files = true;

import "google/protobuf/timestamp.proto";

service RulesetManagementService {
  rpc CreateRulesetVersion(CreateRulesetVersionRequest) returns (RulesetVersion);
  rpc GetActiveRulesetVersion(GetActiveRulesetVersionRequest) returns (RulesetVersion);
  rpc ListRulesetVersions(ListRulesetVersionsRequest) returns (ListRulesetVersionsResponse);
  rpc ActivateRulesetVersion(ActivateRulesetVersionRequest) returns (RulesetVersion);
  rpc DeleteRulesetVersion(DeleteRulesetVersionRequest) returns (DeleteRulesetVersionResponse);
}

enum RulesetMode {
  RULESET_MODE_UNSPECIFIED = 0;
  STATELESS = 1;   // Section 7.2 only - no fact-window read/write, no session_key required
  STATEFUL = 2;    // Section 7.4 - fact-window read before firing, write after; session_key required
}

message RulesetVersion {
  string ruleset = 1;
  int64 version = 2;
  string drl = 3;                    // full DRL source for this version
  bool active = 4;
  RulesetMode mode = 5;
  // Per-ruleset fact-window bounds (Section 7.4). Meaningful only when mode == STATEFUL; ignored
  // otherwise. Both are required (no implicit default) when creating a STATEFUL version - this is
  // why they live on RulesetVersion itself rather than as a global constant (Section 7.4).
  int32 max_window_size = 6;
  int32 idle_timeout_seconds = 7;
  google.protobuf.Timestamp created_at = 8;
  string created_by = 9;              // opaque caller-supplied identity string - see Section 7.3,
                                       // this project has no actor/identity model of its own
}

message CreateRulesetVersionRequest {
  string ruleset = 1;
  string drl = 2;
  RulesetMode mode = 3;
  int32 max_window_size = 4;
  int32 idle_timeout_seconds = 5;
  string created_by = 6;
  // If true, this version becomes active immediately on creation (replacing whichever version
  // was previously active for this ruleset name). If false, it is created inactive - the caller
  // must call ActivateRulesetVersion separately. Default false: creating a version must never be
  // an accidental activation.
  bool activate = 7;
}

message GetActiveRulesetVersionRequest {
  string ruleset = 1;
}

message ListRulesetVersionsRequest {
  string ruleset = 1;
  string cursor = 2;
  int32 limit = 3;
}

message ListRulesetVersionsResponse {
  repeated RulesetVersion items = 1;
  string next_cursor = 2;
}

message ActivateRulesetVersionRequest {
  string ruleset = 1;
  int64 version = 2;
}

message DeleteRulesetVersionRequest {
  string ruleset = 1;
  int64 version = 2;
}

message DeleteRulesetVersionResponse {
  bool deleted = 1;
}
```

**Validation rules, non-negotiable**:
- `CreateRulesetVersion` MUST compile the supplied DRL (Section 7.2's `KieServices.newKieBuilder`
  path) *before* persisting it. A DRL string that fails to compile must be rejected with
  `INVALID_ARGUMENT` and never reach the database. Never persist unparseable DRL "for later fixing."
- `mode` is immutable once a version is created (a ruleset cannot silently change from stateless to
  stateful between calls in a caller's eyes without a new version). Do not add an `UpdateMode` RPC.
- `CreateRulesetVersion` with `mode == STATEFUL` and `max_window_size <= 0` or
  `idle_timeout_seconds <= 0` is `INVALID_ARGUMENT` — a STATEFUL version with no real bound is not
  a valid STATEFUL version, and the fields carry no default worth silently applying (Section 7.4).
  Both fields are ignored (may be left zero) when `mode == STATELESS`.
- `EvaluationService.Evaluate` against a `STATEFUL` ruleset with an empty `session_key` is
  `INVALID_ARGUMENT`, not a silent fallback to stateless behavior.

### 6.3 API specifications and generated language bindings

**There is exactly one copy of `evaluation.proto`/`ruleset_management.proto` in this repository**:
`decision-engine-api-specifications/src/main/resources/META-INF/proto/forwardmeasure/decisionengine/v1/`.
This module is a plain resources jar, mirroring `agent-os-api-specifications`'s own
`META-INF/openapi` convention in `forwardmeasure-agent-os` exactly. Structured by protocol
(`META-INF/proto/...` today) specifically so a Thrift interface can be added later as a sibling
directory (`META-INF/thrift/...`) without restructuring this module or anything downstream of it —
a real, planned future addition (Section 1), not a hypothetical one.

`decision-engine-api-language-bindings` produces java (this project's own runtime dependency),
python, and typescript bindings, split first by language then by **protocol**
(`java/grpc`, `python/grpc`, `typescript/grpc` today — `java/thrift` etc. as a sibling later), each
module **unpacking `decision-engine-api-specifications`'s packaged jar and generating from that
unpacked copy** — never reading or copying the `.proto` source files directly. This is not a
stylistic preference: a language-binding module that keeps its own copy of the `.proto` files is
exactly the mistake this section exists to prevent — two source-of-truth copies drift the moment
one of them is edited and the other is forgotten. If you find yourself about to put a `.proto` file
anywhere other than `decision-engine-api-specifications`, stop.

Java generation uses `protobuf-maven-plugin`, with the canonical sources unpacked first via
`maven-dependency-plugin`. The compiler and gRPC generator are resolved by Maven using
`protocArtifact` and `pluginArtifact`; no globally installed `protoc` is required. The verified
configuration lives in `decision-engine-api-language-bindings/java/grpc/pom.xml` and generates the
full `EvaluationServiceGrpc`/`RulesetManagementServiceGrpc` stub set:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <executions>
    <execution>
      <id>unpack-proto-spec</id>
      <phase>initialize</phase>
      <goals><goal>unpack</goal></goals>
      <configuration>
        <artifactItems>
          <artifactItem>
            <groupId>${project.groupId}</groupId>
            <artifactId>decision-engine-api-specifications</artifactId>
            <version>${project.version}</version>
            <type>jar</type>
            <includes>META-INF/proto/**</includes>
            <outputDirectory>${project.build.directory}/unpacked-proto-spec</outputDirectory>
          </artifactItem>
        </artifactItems>
      </configuration>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.xolstice.maven.plugins</groupId>
  <artifactId>protobuf-maven-plugin</artifactId>
  <version>0.6.1</version>
  <executions>
    <execution>
      <phase>generate-sources</phase>
      <goals><goal>compile</goal><goal>compile-custom</goal></goals>
      <configuration>
        <protoSourceRoot>${project.build.directory}/unpacked-proto-spec/META-INF/proto</protoSourceRoot>
        <protocArtifact>com.google.protobuf:protoc:${protobuf.version}:exe:${os.detected.classifier}</protocArtifact>
        <pluginId>grpc-java</pluginId>
        <pluginArtifact>io.grpc:protoc-gen-grpc-java:${grpc.version}:exe:${os.detected.classifier}</pluginArtifact>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The compiler and generator are Maven-resolved, platform-specific build artifacts. They are not
installed globally and are not expected to exist on `PATH`. The protobuf runtime and gRPC versions
remain those managed by the platform parent.

Python and TypeScript bindings: standard `protoc` + `grpc_tools.protoc` (python) /
`ts-proto` or `@grpc/grpc-js` codegen (typescript) invoked the same way
`agent-os-api-language-bindings/python` and `/typescript` invoke `openapi-generator` — one Maven
module per language, `generate-sources`-phase execution, output staged as a build artifact. Do not
hand-write these bindings.

---

## 7. `decision-engine-core`, `decision-engine-domain`, `decision-engine-jpa`, `decision-engine-fact-window`

### 7.1 Ruleset DRL convention

A ruleset is **pure DRL** — no Java class is ever written to support a new ruleset. The engine only
ever inserts generic `java.util.Map<String, Object>` facts. If a ruleset author wants typed
matching, they use Drools' native `declare` block and extract fields from the inserted `Map`
themselves — this is a ruleset-authoring concern, not something the engine needs to know about.

**Every ruleset MUST declare exactly one global named `result` of type `java.util.Map`.** This is
the mechanism rules use to communicate their decision back to the engine (Drools' "global" concept
— a well-known variable rules can read and mutate, distinct from inserted/retracted facts). The
engine reads this global back after `fireAllRules()` completes. Keep this convention parallel to how
the OPA-based investigation's Rego policies set `outcome`/`reason`/`obligations` as top-level
bindings — same shape, different engine.

Example stateless ruleset:

```drl
package agentos.execution.actionGate

global java.util.Map result

rule "fund switch within materiality threshold"
when
    $input : Map( this["proposedAction.type"] == "recommend-fund-switch",
                  this["proposedAction.amountUsd"] <= 50000 )
then
    result.put("outcome", "PERMITTED");
    result.put("reason", "within materiality threshold");
end
```

Example stateful ruleset (relies on the fact window in Section 7.4 having inserted prior calls'
facts into the same session before this rule ever gets a chance to fire):

```drl
package agentos.execution.oscillationGuard

global java.util.Map result

rule "flag repeated large fund switches"
when
    $count : Number( intValue >= 3 ) from accumulate(
        Map( this["proposedAction.type"] == "recommend-fund-switch",
             this["proposedAction.amountUsd"] > 50000 );
        count(1)
    )
then
    result.put("outcome", "REQUIRES_APPROVAL");
    result.put("reason", "3 or more large fund switches proposed in the current window");
end
```

### 7.2 `decision-engine-domain` (no I/O, no framework dependency)

```java
package com.forwardmeasure.decisionengine.domain;

public interface RuleEvaluator {
  EvaluationOutcome evaluate(EvaluationInput input);
}

public record EvaluationInput(
    String ruleset,
    Long pinnedVersion,
    Map<String, Object> facts,
    String sessionKey) {}   // null/blank for stateless rulesets

public record EvaluationOutcome(
    Map<String, Object> result,
    List<String> firedRules,
    long rulesetVersion,
    int factsConsidered) {}

// Plain domain representation of one ruleset version - deliberately not a reuse of the generated
// contract.v1.RulesetVersion (same reasoning as RulesetMode below). Field set matches the
// CreateRulesetVersionRequest/RulesetVersion proto messages exactly (Section 6.2).
// maxWindowSize/idleTimeoutSeconds are meaningful only when mode == STATEFUL.
public record RulesetVersion(
    String ruleset,
    long version,
    String drl,
    boolean active,
    RulesetMode mode,
    int maxWindowSize,
    int idleTimeoutSeconds,
    java.time.Instant createdAt,
    String createdBy) {}

// The port DroolsRuleEvaluator (decision-engine-core) uses to resolve which DRL/mode/window
// bounds to evaluate against, given a ruleset name and an optional pinned version
// (EvaluationInput.pinnedVersion). The only real implementation is a thin adapter in
// decision-engine-jpa over RulesetVersionService (Section 7.3) - core never talks to JPA/Postgres
// directly. Both methods throw RulesetNotFoundException, never return null, when no matching
// version exists.
public interface RulesetSource {
  RulesetVersion getActiveVersion(String ruleset);
  RulesetVersion getVersion(String ruleset, long version);
}
```

`EvaluationOutcome` and `RulesetVersion` are plain records, not Lombok `@Data` classes — these have
real behavior-adjacent meaning (a decision; an immutable versioned definition), not just
bag-of-fields boilerplate.

Exceptions, mirroring `PolicyNotFoundException` / `PolicyEvaluationUnavailableException` from the
parallel OPA investigation exactly — same two failure shapes apply here for the same underlying
reasons:

- `RulesetNotFoundException` — no active version exists for the requested ruleset name (or the
  pinned version doesn't exist). Maps to gRPC `NOT_FOUND`.
- `RuleEvaluationException` — carries a `Reason` enum, precisely so `decision-engine-grpc` maps
  each cause to the correct gRPC status by switching on an enum, never by string-matching
  `getMessage()`:
  - `INVALID_SESSION_KEY` — a `STATEFUL` ruleset was called with a null/blank `session_key`. Maps
    to `INVALID_ARGUMENT`.
  - `MISSING_OUTCOME` — the ruleset's rules fired but never set `result.get("outcome")` (or set it
    to null). Maps to `FAILED_PRECONDITION` (rules fired, contract violated). **Fail closed: a
    ruleset that doesn't produce a real outcome must never be treated as an implicit permit or an
    implicit anything** — this is the single most important behavioral requirement carried over
    from the OPA investigation and it applies with equal force here.
  - `FACT_WINDOW_UNAVAILABLE` — the `FactWindowStore` was unreachable for a `STATEFUL` ruleset;
    never silently fall back to an empty window (Section 7.4). Maps to `UNAVAILABLE`.
  - `EVALUATION_FAILURE` — the `KieSession` threw during firing (a Drools-level runtime failure,
    not a validation failure — DRL that fails to compile is rejected at `CreateRulesetVersion`
    time, Section 6.2/7.3, and never reaches evaluation as this reason). Maps to `INTERNAL`.

### 7.3 `decision-engine-jpa` — ruleset **definition** persistence

**Mirror `forwardmeasure-agent-os`'s own persistence conventions exactly.** That project's
`agent-os-database-migrations` / `AgentOsTenantMigrator` / `agent-os-database-migration-service`
were themselves a direct, deliberate port of `openworkflow-migrations`'s own real pattern — this
project should be a direct port of the *agent-os* version, one level further down the same chain,
not a fresh design:

- Two-credential model: a bounded migration Job connects as an **administrator** credential
  (creates the schema, runs Liquibase, creates/rotates a scoped **runtime** role via `GRANT` +
  `ALTER DEFAULT PRIVILEGES`); the running server connects only as the runtime role, never the
  administrator credential. Same environment variable naming convention agent-os uses:
  `DECISION_ENGINE_DATABASE_URL` / `_USERNAME` / `_PASSWORD` mean the runtime credential in the
  server, the administrator credential in the migration job.
- `RulesetSchemaMigrator` (mirroring `AgentOsTenantMigrator`): `ensureRuntimeRole(password)`,
  `provisionAndMigrate(...)`. **Single-tenant by default** — this project is not a multi-tenant
  SaaS product, it's an internal engine (see Section 13: no tenant model). Provision one fixed
  schema, not a schema-per-tenant scheme.
- `RulesetMigrationsMain`: bounded Job entry point, same shape as `AgentOsMigrationsMain`.

**Entity** (Lombok is appropriate here):

```java
@Entity
@Table(name = "ruleset_version", uniqueConstraints =
    @UniqueConstraint(columnNames = {"ruleset", "ruleset_version"}))
@Getter @Setter
public class RulesetVersionEntity extends AbstractBaseEntity<Long> {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String ruleset;
  // AbstractBaseEntity already owns `version` as its optimistic-lock column.
  @Column(name = "ruleset_version") private long rulesetVersion;
  @Column(columnDefinition = "text") private String drl;
  private boolean active;
  @Enumerated(EnumType.STRING) private RulesetMode mode;
  private int maxWindowSize;         // meaningful only when mode == STATEFUL - Section 6.2
  private int idleTimeoutSeconds;    // meaningful only when mode == STATEFUL - Section 6.2
  private Instant createdAt;
  private String createdBy;
}
```

Extend `AbstractBaseRepository<RulesetVersionEntity, Long>` (from `forwardmeasure-jpa-core`) for
`RulesetVersionRepository`, exactly the pattern used throughout `forwardmeasure-agent-os`. Use
MapStruct (`RulesetVersionMapper`, with an explicit `version` ↔ `rulesetVersion` mapping) to convert between `RulesetVersionEntity` and the plain
`RulesetVersion` domain record (Section 7.2) — never hand-write this mapping.

**`RulesetVersionService`** — the application-layer boundary this module exposes, both to
`decision-engine-grpc`'s `RulesetManagementServiceImpl` directly and, via a thin `RulesetSource`
adapter (Section 7.2), to `decision-engine-core`'s `DroolsRuleEvaluator`:

```java
package com.forwardmeasure.decisionengine.jpa;

public interface RulesetVersionService {
  RulesetVersion create(String ruleset, String drl, RulesetMode mode,
      int maxWindowSize, int idleTimeoutSeconds, String createdBy, boolean activate);
  RulesetVersion getActive(String ruleset);              // throws RulesetNotFoundException
  RulesetVersion get(String ruleset, long version);       // throws RulesetNotFoundException
  List<RulesetVersion> list(String ruleset, String cursor, int limit);
  RulesetVersion activate(String ruleset, long version);  // throws RulesetNotFoundException
  boolean delete(String ruleset, long version);           // throws RulesetNotFoundException,
                                                            // IllegalStateException if active
}
```

**`create` validation, enforced here before a row is ever written** (Section 6.2's "Validation
rules, non-negotiable", made concrete):
- `ruleset` MUST match `^[a-z][a-zA-Z0-9]*(/[a-z][a-zA-Z0-9]*)*$` (the same pattern
  `EvaluationRequest.ruleset` is validated against, Section 6.1) — reject with `INVALID_ARGUMENT`
  before compiling the DRL. A ruleset name that `Evaluate` would later reject must never be
  creatable in the first place.
- The supplied `drl` MUST compile (`KieServices.newKieBuilder`, Section 7.2's evaluator path)
  *before* persisting. Never persist unparseable DRL "for later fixing."
- The compiled DRL's source text MUST declare **exactly one** `global java.util.Map result;` (or
  the fully-qualified equivalent) — validate this against the DRL source text with a simple,
  explicit check (e.g. a regex over `global\s+java\.util\.Map\s+result\s*;`), not by introspecting
  the compiled `KieBase`'s runtime globals — a global's *declaration* is a static, textual property
  of the DRL, and depends on no `KieSession` having ever run. Zero or more-than-one `result`
  declarations of the required type is `INVALID_ARGUMENT`. This is what guarantees
  `DroolsRuleEvaluator` always has a well-defined global to read back after firing, independent of
  whether any rule happened to touch it (Section 7.2's `MISSING_OUTCOME` reason covers the case
  where the declaration exists but no rule ever populated `outcome` in it).
- If `mode == STATEFUL`: `maxWindowSize > 0` and `idleTimeoutSeconds > 0`, else `INVALID_ARGUMENT`
  (Section 6.2). If `mode == STATELESS`, both are ignored/stored as-supplied without validation.

**Activation invariant, enforced at the database, not just in application code**: a partial unique
index is the actual guarantee — application-level "deactivate then activate in one transaction"
alone is not sufficient defense against every possible race or bug, and the index makes the
invariant impossible to violate regardless:

```sql
CREATE UNIQUE INDEX uq_ruleset_version_active ON ruleset_version (ruleset) WHERE active = true;
```

`activate(ruleset, version)` runs `UPDATE ruleset_version SET active = false WHERE ruleset = ?
AND active = true` followed by `UPDATE ruleset_version SET active = true WHERE ruleset = ? AND
version = ?`, both inside one `@Transactional` method at the default isolation level. Because both
statements commit together, no other transaction can observe an intermediate zero-active state —
ordinary read-committed visibility, not a special isolation level, is what Postgres already
guarantees for an uncommitted transaction's intermediate writes. The unique index exists as a
hard backstop against `activate` ever being reachable in a way that would produce two actives
(e.g. a future bug, a retried request, a bypassed service layer) — it is not the primary mechanism,
it is the guarantee that makes the primary mechanism trustworthy.

**`delete(ruleset, version)` of the currently active version is disallowed** — throw
`IllegalStateException` (mapped to `FAILED_PRECONDITION` in `decision-engine-grpc`), requiring the
caller to `activate` a different version first. This is what makes "a ruleset has zero active
versions" unreachable through the API surface at all, rather than merely unlikely: the only two ways
a ruleset can have zero active versions are "no version was ever created" (not a state `delete`
can produce) or "the sole existing version is deleted" (which this rule also blocks unless the
ruleset has other versions to activate first, and even then only after activation of a different
version succeeds).

**Testing**: a real Testcontainers-Postgres integration test for `RulesetVersionService`
(`RulesetVersionServicePostgreSqlIntegrationTest`), mirroring
`AgentExecutionServiceImplPostgreSqlIntegrationTest`'s shape from `forwardmeasure-agent-os` exactly.
Must include: concurrent `activate` calls for the same ruleset never leave two actives (assert via
the unique index, not just application-level timing); `delete` of the active version throws;
`create` with malformed `result` global declaration is rejected before any row exists.

### 7.4 `decision-engine-fact-window` — stateful-ruleset fact **history** (Valkey, not Postgres)

**This is not a cache-as-optimization. It is the mechanism that makes `STATEFUL` rulesets actually
work.** Read this whole subsection before implementing `DroolsRuleEvaluator`'s stateful branch.

**Why Valkey and not Postgres for this**: a stateful ruleset needs its accumulated recent facts
inserted into working memory *before* rules fire, on every call. If that history lived in Postgres,
every stateful evaluation would need a synchronous write (append the new fact) and a synchronous
read (load the window) against a relational database before a single rule ever fires — turning an
in-memory rule-firing operation into a database-round-trip-bound one, under sustained write
pressure from every stateful evaluation in the system. Valkey is purpose-built for exactly this
shape of state (ephemeral, bounded, high-frequency, shared across replicas, no ACID requirement) —
sub-millisecond round-trips instead of database ones, and its native bounded-list and TTL primitives
give retention/eviction for free, with no cleanup job to write or schedule.

**Why not just an in-memory session held open per `session_key`, avoiding a network hop entirely**:
because this server may run more than one replica, and a given `session_key`'s calls are not
guaranteed to land on the same pod (normal gRPC load balancing has no session affinity here, and
none should be added — sticky routing is an anti-pattern for this deployment shape, see Section
13). An in-memory session would silently and unpredictably lose history the moment a call landed on
a different pod, or the moment its pod restarted. Valkey, being external and shared, does not have
this problem: any replica can serve any call for any `session_key`.

**Port**:

```java
package com.forwardmeasure.decisionengine.domain;

public interface FactWindowStore {
  // Appends `fact` to the window for (ruleset, rulesetVersion, sessionKey), enforcing the
  // ruleset's own configured bound (RulesetVersion.maxWindowSize / idleTimeoutSeconds - Section
  // 6.2), and returns the full current window (oldest first) including the fact just appended -
  // one round trip (a single atomic server-side script, below), not several. The bounds are
  // passed in per call, not read from any store-level config, because they are per-ruleset - this
  // port must never own a default.
  List<Map<String, Object>> appendAndLoad(
      String ruleset, long rulesetVersion, String sessionKey, Map<String, Object> fact,
      int maxWindowSize, int idleTimeoutSeconds);
}
```

**Key is scoped by ruleset version, not just ruleset+session**: `RulesetVersion` is on the key
precisely because a session can span an active-version change (or move between pinned versions),
and a DRL's schema/rule assumptions can differ across versions — mixing facts accumulated under a
different DRL into a newly-firing version's working memory is a correctness bug waiting to happen,
not a convenience worth the risk. Moving to a new version for an in-progress `session_key` therefore
starts a fresh window **by construction of the key**, not by any special-cased application check.

**Valkey-backed implementation** (`ValkeyFactWindowStore` in `decision-engine-fact-window`): key
each window as `decision-engine:fact-window:{ruleset}:{rulesetVersion}:{sessionKey}` (a native
Valkey list of JSON-serialized fact strings — prefer the native list type over a single
JSON-serialized-list value, since it makes the atomic operation below a single native command
rather than a read-modify-write of a blob).

**Atomicity — a single server-side Lua script, not four separate commands**: `RPUSH`, `LTRIM`,
`EXPIRE`, and `LRANGE` run as four separate round trips are not atomic — under concurrent calls
for the same `(ruleset, rulesetVersion, sessionKey)`, interleaving between them can produce a
window `LRANGE` returns that does not reflect the `LTRIM`/`EXPIRE` that logically "belongs" with
the `RPUSH` that just ran. Valkey (like Redis) guarantees a Lua script passed to `EVAL` runs as one
atomic, uninterruptible unit relative to every other command — this is what actually delivers the
"one round trip" this port's Javadoc promises. Implement `appendAndLoad` as one `EVAL` (via
Lettuce's `RedisScriptingCommands.eval`) that does, in order: `RPUSH` the new fact, `LTRIM` to
`maxWindowSize`, `EXPIRE` to `idleTimeoutSeconds`, `LRANGE 0 -1` and return that. Cache the script's
SHA (via `SCRIPT LOAD`/`EVALSHA`) to avoid re-sending the script body on every call; fall back to a
plain `EVAL` on a `NOSCRIPT` response.

**Concurrent-call ordering guarantee, stated explicitly because callers must not assume more than
this**: the atomic script guarantees no lost updates and no torn reads — two concurrent
`appendAndLoad` calls for the same session will each see a window that reflects both appends, in
whatever order Valkey happened to serialize the two script executions (Valkey, like Redis, executes
all commands — including scripts — single-threaded, so *some* total order is always guaranteed).
**This store does not guarantee that order matches call-issue order across replicas** — if a caller
needs facts inserted in a specific wall-clock order for a given `session_key`, the caller is
responsible for serializing its own calls for that `session_key` (e.g. awaiting each `Evaluate`
response before issuing the next for the same session); this store's job is only to make each
individual append-and-load atomic and lossless, not to impose an ordering policy the engine has no
way to know the right answer to.

Both bound values (max length, idle timeout) are **per-ruleset configuration**, not global
constants — `RulesetVersion.max_window_size` / `idle_timeout_seconds` (Section 6.2) already carry
them, and `FactWindowStore.appendAndLoad` (above) takes them as call parameters for exactly this
reason, so different stateful rulesets can size their own window appropriately (the
oscillation-guard example in Section 7.1 might reasonably want a 1-hour window of the last 50
facts; a different ruleset might want something else entirely — this project does not get to
assume one bound fits every stateful ruleset that will ever be created).

**Fail-closed rule for this store, same principle as everywhere else in this document**: if Valkey
is unreachable when a `STATEFUL` ruleset is evaluated, the call fails (`INTERNAL`, or a more
specific `UNAVAILABLE` if the gRPC status vocabulary supports distinguishing "the fact store is
down" from other internal failures — prefer the more specific status). **Do not silently fall back
to evaluating with an empty window** — that would silently and invisibly downgrade a stateful
ruleset's actual behavior to stateless the moment Valkey has a bad moment, which is exactly the
"looks fine, quietly does the wrong thing" failure mode every fail-closed rule in this document
exists to prevent.

**`DroolsRuleEvaluator`'s stateful branch** (extending the sketch already given for the stateless
path in the original design pass — combine them into one method that branches on `RulesetMode`):

```java
if (version.mode() == RulesetMode.STATEFUL) {
  if (input.sessionKey() == null || input.sessionKey().isBlank()) {
    throw new RuleEvaluationException(ruleset, version.version(),
        RuleEvaluationException.Reason.INVALID_SESSION_KEY,
        "STATEFUL ruleset called without a session_key");
  }
  List<Map<String, Object>> window;
  try {
    window = factWindowStore.appendAndLoad(
        ruleset, version.version(), input.sessionKey(), input.facts(),
        version.maxWindowSize(), version.idleTimeoutSeconds());
  } catch (RuntimeException e) {
    throw new RuleEvaluationException(ruleset, version.version(),
        RuleEvaluationException.Reason.FACT_WINDOW_UNAVAILABLE,
        "fact window store unreachable", e);
  }
  window.forEach(session::insert);   // insert the whole window, oldest first, into the fresh session
} else {
  session.insert(input.facts());     // stateless: exactly the original single-fact path
}
```

The `KieSession` itself is still created fresh and disposed at the end of every single call, for
both modes — Section 7.4 does not reintroduce a long-lived `KieSession`; it reintroduces the
*facts* a fresh session needs to see, sourced from Valkey instead of from an in-process session's
own retained memory. This is the whole point: statefulness lives in the fact window, not in the
session object.

**Testing**: a real Testcontainers-backed test for `ValkeyFactWindowStore` (confirm whether
`forwardmeasure-testcontainers` already has a Valkey/Redis module; if not, add one there first,
following that repository's own existing module conventions, rather than hand-rolling a one-off
Testcontainers setup in this repo) proving: the window grows across repeated `appendAndLoad` calls,
is capped at `max_window_size`, and expires after `idle_timeout_seconds` of inactivity. Also a
`DroolsRuleEvaluator` test using the oscillation-guard-shaped example ruleset from Section 7.1,
proving a rule that only fires on the *third* call for a given `session_key` behaves correctly —
this is the test that actually proves the CEP capability this whole project exists to deliver; do
not consider this module done without it passing against a real Valkey container.

---

## 8. Framework bindings

### 8.1 `decision-engine-grpc` — the shared, framework-neutral service implementations

```java
package com.forwardmeasure.decisionengine.grpc;

public class EvaluationServiceImpl extends EvaluationServiceGrpc.EvaluationServiceImplBase {
  private final RuleEvaluator evaluator;
  // constructor injection, no framework annotations on this class at all

  @Override
  public void evaluate(EvaluationRequest request, StreamObserver<EvaluationResponse> responseObserver) {
    try {
      EvaluationOutcome outcome = evaluator.evaluate(new EvaluationInput(
          request.getRuleset(),
          request.hasRulesetVersion() ? request.getRulesetVersion() : null,
          structToMap(request.getInput()),
          request.getSessionKey()));
      responseObserver.onNext(EvaluationResponse.newBuilder()
          .setResult(mapToStruct(outcome.result()))
          .addAllFiredRules(outcome.firedRules())
          .setRulesetVersion(outcome.rulesetVersion())
          .setFactsConsidered(outcome.factsConsidered())
          .setCorrelationId(request.getCorrelationId())
          .build());
      responseObserver.onCompleted();
    } catch (RulesetNotFoundException e) {
      responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
    } catch (RuleEvaluationException e) {
      responseObserver.onError(mapRuleEvaluationFailure(e));
    } catch (RuntimeException e) {
      responseObserver.onError(Status.INTERNAL.withDescription("unexpected failure").asRuntimeException());
    }
  }
}
```

(`ManagementServiceImpl` follows the identical shape against `RulesetManagementService` /
`RulesetVersionService` — same error-mapping discipline applies: a DRL compile failure or a
malformed/missing `result` global declaration on `CreateRulesetVersion` maps to `INVALID_ARGUMENT`,
a `delete` of the active version maps to `FAILED_PRECONDITION`, not `INTERNAL`.)

`mapRuleEvaluationFailure` switches on `RuleEvaluationException.Reason` (Section 7.2) — a plain,
exhaustive switch, not string-matching `getMessage()`:

```java
private static StatusRuntimeException mapRuleEvaluationFailure(RuleEvaluationException e) {
  Status status = switch (e.reason()) {
    case INVALID_SESSION_KEY -> Status.INVALID_ARGUMENT;
    case MISSING_OUTCOME -> Status.FAILED_PRECONDITION;
    case FACT_WINDOW_UNAVAILABLE -> Status.UNAVAILABLE;
    case EVALUATION_FAILURE -> Status.INTERNAL;
  };
  return status.withDescription(e.getMessage()).withCause(e).asRuntimeException();
}
```

`structToMap` / `mapToStruct` (Struct ⇄ `Map<String,Object>`) belong in this module too, as a small
internal utility class — use `com.google.protobuf.util.JsonFormat` or direct `Struct.getFieldsMap()`
traversal, not a hand-rolled recursive-descent converter if the protobuf-java-util library already
provides one.

**`mapToStruct` is the only place a `RulesetMode.STATEFUL`-vs-`STATELESS`-agnostic correctness gap
in `result` becomes visible, and it too must fail closed**: `result` is a plain, DRL-author-supplied
`Map<String, Object>` with no compile-time constraint on its value types (Section 7.1) — a rule
author can legally `result.put("processedAt", someDateInstance)` or put any other type
`com.google.protobuf.Struct` cannot represent (only `String`/`Number`/`Boolean`/`Map`/`List`/`null`
convert). Converting such a value must throw `RuleEvaluationException` with reason
`MISSING_OUTCOME` (this is, functionally, a contract-violation case — a `result` the engine cannot
actually deliver back to the caller is no better than one that never set `outcome`) rather than let
a `Struct`-building library throw an unrelated, unmapped runtime exception that falls through to the
generic `INTERNAL`/"unexpected failure" catch-all above. Do not let ruleset authors discover this
the hard way in production; this is exactly the kind of validation Section 7.3's DRL-authoring
guidance should also warn about.

**This module has no `@GrpcService`, no Spring `@Bean`, no Micronaut `@Singleton` anywhere in it.**
Those annotations belong exclusively in the three `framework-bindings/*` modules below.

### 8.2 Quarkus binding

- Dependencies: `quarkus-grpc`, plus the Postgres/Hibernate set already proven in
  `agent-os-execution-quarkus` (no `drools-quarkus` — see Section 13). **No Quarkus-specific Redis/
  Valkey extension** — `decision-engine-fact-window`'s plain Lettuce dependency (Section 7.4) is
  used as-is here too; this module has no client library decision of its own to make.
- `EvaluationServiceImpl` and `ManagementServiceImpl` are constructed as CDI `@Produces` beans in a
  binding class (same shape as `AgentPolicyQuarkusBinding` in `forwardmeasure-agent-os`), then
  annotated for gRPC discovery via `@GrpcService` (verify the exact current mechanism against
  Quarkus's own gRPC docs — whether that annotation applies directly to a `@Produces`-returned type
  or needs a thin subclass — don't guess).
- **Apply the JSON-column-format fix discovered during the parallel agent-os investigation from day
  one**: this project has a `drl` text column and JSON-shaped facts flowing through it — the same
  Quarkus/Jackson JSON-column-format conflict (`quarkus.hibernate-orm.mapping.format.global:
  ignore`) is expected here too. Set it from the start; don't rediscover it the hard way.

### 8.3 Spring binding

- Dependencies: `spring-boot-grpc-server` (first-party, Spring Boot 4.1+) — **not**
  `net.devh:grpc-server-spring-boot-starter` and **not** any KIE Spring starter (Section 13).
  **No Spring-specific Redis/Valkey starter** — `decision-engine-fact-window`'s plain Lettuce
  dependency (Section 7.4) is used as-is here too.
- Register `EvaluationServiceImpl` / `ManagementServiceImpl` as plain `@Bean`s; Spring Boot 4.1's
  gRPC auto-configuration discovers `BindableService` beans automatically (verify the exact
  discovery mechanism against current `spring-boot-grpc-server` docs before relying on it — this is
  a very recently shipped feature).
- **Apply the `EntityManager` fix already discovered in the parallel `forwardmeasure-agent-os`
  investigation, from day one**: `spring-boot-starter-data-jpa` registers `EntityManagerFactory`
  but never a plain injectable `EntityManager` bean. Check first whether
  `forwardmeasure-jpa-spring`'s `ForwardMeasureJpaAutoConfiguration` has since been fixed at the
  source (a fix was proposed there during the agent-os work); if not yet released, add a local
  `SharedEntityManagerCreator`-based `@Bean EntityManager` producer in this project's own Spring
  binding, same as agent-os's bindings did before that upstream fix existed.

### 8.4 Micronaut binding

- Dependencies: `micronaut-grpc-server-runtime` (verify current artifact IDs — Section 3).
  **No Micronaut-specific Redis/Valkey module** — `decision-engine-fact-window`'s plain Lettuce
  dependency (Section 7.4) is used as-is here too.
- Because Micronaut uses compile-time DI (no runtime classpath scanning of arbitrary framework-
  neutral classes), the shared `EvaluationServiceImpl` / `ManagementServiceImpl` from
  `decision-engine-grpc` need a **thin annotated subclass** in this module for Micronaut to discover
  them at compile time — this exact pattern, already proven working in
  `forwardmeasure-agent-os`'s `MicronautPolicyEvaluationsResource extends PolicyEvaluationsResource`:

  ```java
  @Singleton
  public final class MicronautEvaluationService extends EvaluationServiceImpl {
    @Inject
    public MicronautEvaluationService(RuleEvaluator evaluator) { super(evaluator); }
  }
  ```
- `MicronautTransactionalServiceProxy`-style wrapping is needed for `RulesetVersionService` (JPA-
  backed), exactly as in `forwardmeasure-agent-os`'s own Micronaut bindings — port that utility
  class directly. It is **not** needed for `ValkeyFactWindowStore` (no JPA/transactional concerns
  there at all).

---

## 9. Deployment — self-contained, inside this repository

This project deploys itself. It does not publish a chart through the shared `helm-charts`
repository and does not register a release in `forwardmeasure-platform/deploy`'s helmfile. Both
remain possible *additive* integrations later if desired, but this repository must be fully
installable on its own with nothing beyond what lives in `deploy/`.

### 9.1 `deploy/helm/decision-engine` — the chart

One chart, parameterized by framework choice (`image.repository` picks which of the three built
images to run — this project does not need three separate charts, only three container images; the
chart's shape — Deployment, Service, ServiceAccount, gRPC health probe, resource limits — is
identical regardless of which framework built the image inside it). Mirror the
`opa-helm-chart`'s own structure and conventions from the parallel investigation (`Chart.yaml`,
`_helpers.tpl`, `values.yaml`, `templates/`) as the direct template — same house style, same
`helm-chart-sources`-free flat layout is fine here since this chart is never published externally
through `helm-charts`' own tooling.

**This chart's own `values.yaml` has no `embedded`/`external` concept at all, by design** — it
carries only a plain, already-resolved `factWindow.valkey.{host,port,credentialsSecret}`:

```yaml
factWindow:
  valkey:
    host: ""
    port: 6379
    credentialsSecret: ""
```

**The embedded-vs-external toggle itself lives one layer up, in `deploy/helmfile/` (Section 9.2),
not in this chart.** This is a deliberate boundary, not an oversight: this chart deploys the
decision-engine server only, never Valkey — whether a `decision-engine-valkey` release exists
alongside it in the same helmfile run is an orchestration-time decision the chart has no business
knowing about. The helmfile layer resolves `mode: embedded | external` and hands this chart nothing
more than the `host`/`port` to connect to, exactly as if an operator had typed them in by hand. Do
not add a `mode` field to this chart's own `values.yaml` — if you find yourself wanting to branch on
"embedded vs. external" inside a template under `templates/`, that logic belongs in
`deploy/helmfile/releases/decision-engine/base.yaml.gotmpl` instead (Section 9.2), which already
does exactly this.

Do not implement the embedded-vs-external toggle via Helm's native subchart `condition:` dependency
mechanism (declaring `valkey/valkey` as a conditional dependency in this chart's own `Chart.yaml`)
either — nothing in this ecosystem currently uses that mechanism, and there is an already-proven
alternative to reuse instead (next section). Prefer consistency with existing, working patterns over
Helm's more "native" option when both are equally valid.

### 9.2 `deploy/helmfile/` — orchestration

Mirror `forwardmeasure-platform/deploy/helmfile`'s own structure (`environments/`,
`helmfiles/<stage>.yaml.gotmpl`, `releases/<name>/base.yaml.gotmpl`) at a scale appropriate to one
project rather than the whole platform — this repository needs one stage, not eight.

The Valkey toggle is implemented at the helmfile level with a **conditional release**, the same
`installed: {{ if ... }}true{{ else }}false{{ end }}` mechanism already proven in
`forwardmeasure-platform/deploy/helmfile/helmfiles/10-platform-configuration.yaml.gotmpl` for
`platform-secrets` / `platform-routing`:

```yaml
{{- $valkeyMode := .Values.factWindow.valkey.mode }}

repositories:
  - name: valkey
    url: https://valkey.io/valkey-helm

releases:
  - name: decision-engine-valkey
    namespace: {{ .Values.namespaces.decisionEngine }}
    chart: valkey/valkey
    version: {{ .Values.chartVersions.valkey | quote }}
    installed: {{ if eq $valkeyMode "embedded" }}true{{ else }}false{{ end }}
    labels: {component: fact-window}

  - name: decision-engine
    namespace: {{ .Values.namespaces.decisionEngine }}
    chart: ../helm/decision-engine
    version: {{ .Values.chartVersions.decisionEngine | quote }}
    labels: {component: server}
    needs:
      {{- if eq $valkeyMode "embedded" }}
      - {{ .Values.namespaces.decisionEngine }}/decision-engine-valkey
      {{- end }}
    values:
      - releases/decision-engine/base.yaml.gotmpl
```

When `mode: embedded`, `releases/decision-engine/base.yaml.gotmpl` points
`factWindow.valkey.external.host` at the in-cluster service DNS name the just-installed
`decision-engine-valkey` release produces (same-namespace `Service`, standard Kubernetes DNS);
when `mode: external`, it passes through whatever host/port/credentials-secret values the operator
supplied directly. Either way, `decision-engine-fact-window`'s `ValkeyFactWindowStore` connects to a
plain host:port — it has no idea whether that Valkey is one this same helmfile just stood up or a
pre-existing instance elsewhere. That's deliberate: the mode toggle is purely a deployment-time
concern, invisible to the application code.

### 9.3 Container images

`io.fabric8:docker-maven-plugin`, `container-image` Maven profile, one image per framework binding
(`decision-engine-quarkus`, `decision-engine-spring`, `decision-engine-micronaut`) — same
`Dockerfile.jvm` shapes already proven for each framework in `forwardmeasure-agent-os`.

---

## 10. Non-functional requirements

- **Fail closed, always** (Sections 7.2, 7.4). Repeated deliberately — it is the single most
  important behavioral property of the whole system and the easiest one to silently violate with a
  "helpful" default.
- **`KieSession.dispose()` in a `finally` block, always**, for both stateless and stateful calls —
  resource leak otherwise. Statefulness lives in the fact window (Valkey), never in a retained
  `KieSession`.
- **DRL compiles before it is persisted, never after** (Section 6.2) — no ruleset in the database is
  ever allowed to be unparseable.
- **Structured logging via SLF4J only**, correlation ID propagated from the gRPC request into every
  log line for that call (an MDC key, set at the top of each service method, cleared in a `finally`).
- Every framework binding must expose a gRPC health-check service (`grpc.health.v1.Health`) — all
  three frameworks' gRPC extensions support this natively; enable it, don't hand-roll it.

---

## 11. Testing requirements (all mandatory, none optional)

### 11.1 `decision-engine-core` unit tests
Real `DroolsRuleEvaluator` tests using real DRL strings (no mocked `KieSession`) for the stateless
path — insert a `Map` fact, fire rules, assert the `result` global came back correctly. Include one
test proving the fail-closed behavior directly: a ruleset whose DRL never sets
`result.get("outcome")` must throw `RuleEvaluationException`.

### 11.2 `decision-engine-jpa` and `decision-engine-fact-window` integration tests
Real Postgres and real Valkey via `forwardmeasure-testcontainers` — no H2, no embedded/mocked Valkey,
no mocked repository. See Section 7.4's own testing requirement for the specific CEP-proving test
that must exist here.

### 11.3 Conformance tests (`decision-engine-conformance-tests`)
A real gRPC client (using `decision-engine-api-language-bindings-java-grpc`) run against **each of the three
running framework-binding containers in turn** — start the container, migrate a real Postgres,
connect to a real Valkey (test both `embedded` and `external` deploy shapes at least once each, not
just one of them — Section 9.1's toggle is a real behavioral fork worth proving both sides of, not
just building), create and activate a ruleset via `RulesetManagementService`, call
`EvaluationService.Evaluate` for both a stateless and a stateful ruleset (the stateful case must
prove the third-call-fires behavior from Section 7.4's own test, end-to-end through gRPC this time,
not just at the `DroolsRuleEvaluator` unit level), then assert malformed/unknown-ruleset/missing-
session-key calls return the correct gRPC status codes. This directly mirrors the 9-way live
verification performed for `forwardmeasure-agent-os` during the parallel investigation — every
framework binding must be proven to actually run, not just compile. Do not report this project
"done" without this evidence.

### 11.4 `decision-engine-architecture-tests`
An ArchUnit rule: no class in `decision-engine-core` or `decision-engine-domain` may depend on any
package under `io.quarkus`, `org.springframework`, or `io.micronaut`. This is the automated
enforcement of Section 4's central architectural rule — write it before writing `DroolsRuleEvaluator`
itself, not after, so a violation fails the build immediately rather than being caught in review.

---

## 12. Definition of done

A milestone in this project is not "done" on the strength of `mvn clean install` passing. It is
done when:

1. `decision-engine-architecture-tests` passes — the core/domain-framework-neutrality rule is real,
   not aspirational.
2. Every one of the three framework bindings has been built into a real container image and
   actually run as a container against a real Postgres **and** a real Valkey (Section 11.3) — not
   just compiled.
3. The fail-closed behavior (Sections 7.2, 7.4) has passing tests that would fail if someone "fixed"
   them to silently default `outcome` to something, or to silently evaluate a stateful ruleset with
   an empty window when Valkey is unreachable, instead of throwing.
4. The oscillation-guard-shaped stateful test (Section 7.4) passes end-to-end through gRPC (Section
   11.3), proving a rule that only fires on a session's *third* call actually fires on the third
   call and not the first or second — this is the one piece of evidence that actually justifies this
   project's existence alongside OPA. Nothing else in this list substitutes for it.
5. `deploy/helm/decision-engine` passes `helm lint --strict`, and `helmfile template` against
   `deploy/helmfile/` renders correctly for **both** `factWindow.valkey.mode: embedded` and
   `mode: external` (Section 9.2) — both toggle states, not just the default.

Report progress against this list specifically, not against "which files exist."

---

## 13. Explicit non-goals — do not build these

Codex: treat every item below as an instruction not to do something, not as a list of nice-to-haves
you are free to add if you think of a clean way to do them. If you find yourself about to add one of
these, stop and leave a `// TODO(spec-gap): considered building X, did not because Section 13 rules
it out — flagging in case this was wrong` comment instead.

1. **No `drools-quarkus`, no KIE Spring Boot starter (`kie-server-spring-boot-starter-drools` or
   any other `kiegroup`/`droolsjbpm-integration` starter), no hand-rolled Micronaut-Drools glue
   beyond the thin subclass in Section 8.4.** Every framework touches Drools through exactly one
   path: `decision-engine-core`'s plain `kie-api` usage. This is the entire point of this
   architecture — verify it against Section 11.4 before considering any milestone done.
2. **No REST/JAX-RS surface anywhere.** gRPC only, both services (Section 5).
3. **No multi-tenancy.** One schema, one runtime role, no `TenantScope`, no per-tenant anything.
   Ruleset names may encode a tenant/domain prefix as a caller convention if a caller wants that
   (exactly as OPA's `policyPath` allows today), but this project itself has no concept of a tenant.
4. **No maker-checker / approval workflow for ruleset versions.** Create → optionally activate →
   list → get-active → delete. That is the whole lifecycle. No draft/review/approved states.
5. **No LRU/TTL eviction logic hand-written for the `KieBaseCache`** (the compiled-rules cache in
   `decision-engine-core`, keyed by `(ruleset, version)`, Section 7.4's "reintroducing sketch" refers
   to this too) — a plain unbounded `ConcurrentHashMap` is sufficient; the number of distinct
   `(ruleset, version)` pairs any real deployment holds is small, and a version's DRL is immutable
   once created, so nothing ever needs invalidating, only occasionally growing. This is a
   **different** cache from the fact window (Section 7.4), which *does* need eviction and gets it
   for free from Valkey's native TTL/`LTRIM` — do not confuse the two or apply this non-goal to the
   fact window by mistake.
6. **No sticky/session-affine gRPC routing.** The fact window (Section 7.4) exists specifically so
   this is never needed. If you find yourself reaching for consistent-hash load balancing or
   session-affinity configuration to make statefulness work, that means Section 7.4 was not
   implemented correctly — go back to it rather than adding routing-layer complexity to compensate.
7. **No agent-os awareness whatsoever.** This repository must build, test, and deploy with zero
   knowledge that `forwardmeasure-agent-os` or OPA exist. The `policyPath`-style naming convention
   and `session_key` concept are deliberate parallels for human comparability between the two
   engines, not dependencies.
8. **No authentication/authorization layer of its own.** This is an internal engine, reached only
   from inside the cluster, the same trust model already established for the OPA deployment in this
   ecosystem (no mTLS, no per-caller auth token, reachable only via ClusterIP). If that trust model
   is wrong for this project specifically, that is a decision to surface explicitly, not to route
   around by quietly adding a custom auth layer.
9. **No publishing this chart through the shared `helm-charts` repository, no registering a release
   in `forwardmeasure-platform/deploy`'s helmfile**, for this initial build (Section 9). Both are
   legitimate future integrations if this project graduates from standalone evaluation to adopted
   platform component, but building them now is scope this spec does not ask for.
10. **No Infinispan, no other distributed cache/data-grid technology for the fact window.**
    Considered and rejected during this spec's own design process: Infinispan's embedded mode
    couples cache scaling to application-pod scaling (the exact coupling problem the fact window
    exists to avoid), its server mode requires standing up and operating a second, unproven-in-this-
    ecosystem cluster technology, and no current, verifiable Drools-Infinispan integration exists to
    justify the extra weight. Valkey is already deployed and proven in this ecosystem and is
    sufficient for the actual requirement (a bounded, TTL'd list per session key) — do not
    reconsider this without a concrete, specific reason Valkey cannot satisfy, and if you find one,
    flag it rather than silently substituting a different technology.
