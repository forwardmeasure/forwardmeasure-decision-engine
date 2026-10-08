# FDE rehabilitation and FOWF integration — 2026-10-06

## Packaged gRPC acceptance and Micronaut readiness repair — 2026-10-08

All three framework conformance cases now pass against locally built service images with real
Keycloak, PostgreSQL, Valkey and migration jobs. `/tmp/fde-authenticated-conformance-03-20261008.log`:
three cases, zero failures/errors/skips, BUILD SUCCESS, 1m44s. This is executed packaged-service
evidence, superseding the earlier compile-only status for these scenarios. It does not establish
the FOWF adapter path or both embedded/external Helm shapes.

The conformance test previously sent unsigned tenant headers and was disabled even by `full-suite`.
It now runs independently for each framework with signed tenant identities, explicit audiences and
organization-scoped grants. It exercises golden evaluations, version pinning, administration,
ruleset persistence, physical database isolation and tenant-separated stateful windows using the
same ruleset/version/session key. Negative calls cover unauthenticated callers, audience/tenant
conflicts and denied permissions; an evaluator cannot replace rules or clear caches. PostgreSQL
and Keycloak use shared fixtures; migration jobs own provisioning, with SQL limited to read-only
observations. Shared Valkey fixture consolidation remains outstanding.

The first live run found a shared test-fixture defect (overlapping grants used UNANIMOUS rather
than production's AFFIRMATIVE strategy) and a production Micronaut readiness defect: its probe
accessed a transaction-bound datasource outside a transaction. The Micronaut binding now unwraps
the physical control-plane pool, as the shared tenant registry does. The public gRPC test requires
anonymous liveness and bounded readiness success. It failed on the old image and passes with:
`sha256:18ff556887e7b5e433de85edf27f15f147f43e87f4b494c5d98acc904beca944`.

All 26 modules' production/test sources compile after the repair:
`/tmp/fde-readiness-repair-compile-20261008.log` (15.033s). Only the changed Micronaut image then
needed rebuilding: `/tmp/fde-micronaut-readiness-image-20261008.log` (9.022s). No images were pushed
and no cluster was changed. To reproduce that local image build, with no competing Maven build:

```bash
export JAVA_HOME=/opt/java/jdk-25.0.3+9
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="${MAVEN_OPTS:-} -XX:TieredStopAtLevel=1"
/home/pn/Documents/code/forwardmeasure/forwardmeasure-openworkflow/scripts/build-bounded.sh \
  -f /home/pn/Documents/code/forwardmeasure/forwardmeasure-decision-engine/pom.xml \
  -pl :decision-engine-micronaut -am -Pcontainer-image -B -ntp \
  -DskipTests=true -DskipITs=true -Dmaven.test.skip=false \
  -Dcontainer-image.build=true -Dcontainer-image.push=false -Ddocker.skip.push=true package
```

The JVM workaround above is Maven-only. Do not propagate it into application images or global
`JAVA_TOOL_OPTIONS`. Quarkus/Spring need no additional production rebuild for this Micronaut fix.

## Subsequent deployment-selection source update

The shared deployment selection now defaults to FDE enabled, Quarkus and Kafka Streams; the
opt-in/default-disabled instructions later in this historical record are superseded. Optional
`FORWARDMEASURE_ENABLE_FDE=false` still works for an installation without an existing FDE release;
retiring an installed release requires an explicit operator plan. FDE image/digest selection uses
the same frozen configuration as the other products. The current GCP environment retains its
legacy namespace until coordinated cutover. No cluster change was performed for this update.
See [deployment configuration and cutover](../../../forwardmeasure-platform/docs/deployment-configuration-and-cutover.md)
and [shared cache policy](../../../forwardmeasure-platform/docs/maven-build-cache.md).

## Latest deployment repair: health protocol compatibility

The operator's `/tmp/fowf-fds-fei-fde-install-2026-10-06-retry3.log` ended with the FDE
Deployment exceeding its progress deadline; Helm rolled the release back. Read-only cluster
events identified a startup-probe failure, not an image pull or migration failure:

```text
java.lang.ClassCastException:
grpc.health.v1.HealthOuterClass$HealthCheckRequest
cannot be cast to io.grpc.health.v1.HealthCheckRequest
```

The shared `TenantContextServerInterceptor` assumed grpc-services Java health types. Quarkus
registers different generated Java types for the same protobuf protocol. Both the incoming
request cast and outgoing response cast were wrong for that registration. The interceptor now
uses the call's registered request/response marshallers to bridge the protobuf wire format.
The shared module acquires no Quarkus dependency. Liveness remains independent of databases;
readiness still checks dependencies asynchronously, with one dependency check in flight.

`HealthProtocolCompatibilityTest` in the Quarkus binding uses both actual generated method
descriptors (Quarkus and grpc-services), including their real request and response marshallers.
Six parameterized cases cover liveness, healthy/unhealthy readiness, dependency exceptions,
empty service, unknown service, and missing request. They also reject accidental tenant resolution
and delegation to a downstream handler. This is protocol-boundary coverage, not a packaged-server
or k3s test. Claude must still exercise the shipped images and probes across all frameworks.

All 26 FDE reactor modules, including production and test sources for all frameworks, compiled:

```bash
scripts/build-bounded.sh -B -ntp -DskipTests -DskipITs -Dmaven.test.skip=false test-compile
```

Exit 0, `BUILD SUCCESS`; log `/tmp/fde-health-protocol-compile-2026-10-06.log`.
Tests were not executed. No image was built/pushed or cluster resource changed by this repair.
The failed server used digest
`sha256:49159aafd0d0c9871f4d9a31518dee05a51c15ee21b642d49a94799c964b9b3e`;
the operator subsequently reported pod `decision-engine-7c74b5474f-kcfkc` running with 2/2
containers ready, zero restarts after 16 minutes, and Deployment availability 1/1. That is startup
evidence for the rebuilt deployment, not acceptance of decision evaluation or FOWF integration.

For this incremental repair, rebuild/push **only `forwardmeasure/decision-engine-quarkus:1.1.0`**:

```bash
cd /home/pn/Documents/code/forwardmeasure/forwardmeasure-decision-engine
set -o pipefail
scripts/build-bounded.sh -B -ntp -pl :decision-engine-quarkus -am \
  -Pcontainer-image -Dcontainer-image.push=true -Ddocker.nocache=false \
  -Drat.skip=true -DskipTests -DskipITs -Dmaven.test.skip=false clean install \
  2>&1 | tee /tmp/fde-health-image-2026-10-06.log
```

Then rerun the normal platform installer with `FORWARDMEASURE_ENABLE_FDE=true`; it refreshes the
image digest inventory. The broader image list later in this document describes the original
rehabilitation batch already rebuilt by the operator, not additional images required by this fix.

This is the current FDE production-change and verification handover. It supersedes the September
FDE trust/deployment assumptions. Codex implemented the production changes; Claude should review
these changes and develop independent tests under the shared testing instructions. Compilation and
packaging succeeded; **no behavioral tests have been executed for this batch**. No images, cloud
resources or Kubernetes releases were changed during that implementation stage. The operator
subsequently built/pushed images and deployed FDE as recorded above. Source and test changes are
included in the October 6 deployment checkpoint; behavioral test execution remains outstanding.
The opt-in remains `FORWARDMEASURE_ENABLE_FDE=true`; the default remains disabled until the operator
builds the affected images, applies infrastructure, and runs the installer with that flag.

## What changed and why

| Area | Previous defect | Implemented behavior |
|---|---|---|
| Caller identity | Production default trusted unsigned `tenant-id` metadata | All three frameworks require RS256 JWT verification against configured JWKS, issuer, expiration and audience `decision-engine-api`; missing verification configuration fails startup. A conflicting metadata tenant is rejected. |
| Framework identity propagation | A concrete-class check cannot identify a Quarkus CDI proxy | Verified organization is returned through the resolver interface and propagated in gRPC Context. TenantScope is entered only on the actual service thread. |
| Authorization | A resolved tenant alone could evaluate, change rules and clear caches | Shared AuthZEN enforces separate evaluate/manage/admin capabilities. Evaluation-only service accounts cannot upload executable DRL or administer caches. Tenant registry ACTIVE status is checked for every call, including cache-only administration. |
| Stateful isolation | Valkey keys omitted tenant identity | Every production evaluator namespaces its store by tenant UUID; individually encoded key components prevent separator collisions. The `v2` prefix deliberately does not read previously shared windows. |
| Compiled isolation | KIE's process-wide repository used IDs derived only from ruleset/version | Every compilation gets a unique ReleaseId. Validation, eviction and cache administration dispose containers and remove their repository modules. In-use sessions retain their container until completion. |
| Version identity | Deleting the highest version allowed `max+1` reuse | A new additive migration creates and seeds `ruleset_version_counter`. Allocation, activation and deletion serialize by tenant database/schema/ruleset with transaction-scoped advisory locking. Existing changesets are not edited. |
| Commit ordering | Quarkus service factory/outer RPC transactions could reply before commit | A managed transactional delegate commits inside TenantExecution before the gRPC response. Spring transaction interception and Micronaut's explicit transaction proxy use the same boundary. Quarkus augmentation verified the delegate is proxyable. |
| Resource limits | Tenant evaluator map and rule firing were unbounded | Configurable tenant/cache/firing limits; no container disposal under an active session; Valkey timeout and shutdown cleanup. |
| API details | Default pagination cursor used the wrong limit; caller could spoof creator; nullable result entries failed | Effective page limit determines the cursor; creator comes from verified identity; non-outcome result fields may be null; pinned versions must be positive; prior MDC correlation is restored. |
| Failure diagnostics | Internal evaluation causes could disappear behind gRPC status | Evaluation failures log correlation, ruleset and cause server-side; clients retain stable gRPC status and redacted descriptions. No facts or credentials are deliberately logged. |
| Health | Named gRPC probe ports are invalid; transport health alone ignores dependencies | Numeric port; startup/liveness distinguish process health from readiness; readiness checks control-plane connection and Valkey, off the transport thread with at most one dependency check in flight. |
| Chart runtime | Read-only filesystem lacked explicit writable temporary storage | `/tmp` emptyDir and filesystem group, matching the compiler's needs; configurable gRPC port wired to all framework configurations. Optional init migration requires an explicit tenant list and accepts a digest. |
| Embedded Valkey | Separate Valkey release did not use the password passed to FDE | Embedded mode configures ACL authentication from the same existing Secret. GCP continues using the platform's external Valkey. |
| FOWF credentials | No tenant-bound FDE evaluation credential path | Operator-configured `decision-engine-token` is resolved inside the adapter using the dispatch tenant and shared TenantClientCredentials. The workflow does not select a tenant or receive the underlying client secret. Existing ordinary mounted secrets still work. |
| FOWF egress | Generic gRPC allowlist did not include the platform FDE endpoint | Operator-configured exact endpoint grant is added for FDE; it does not allow other ports, hosts or protocols. |
| Registry/publication | Bundle publisher and chart only accepted YAML/JSON | `.proto` documents publish as Apicurio PROTOBUF artifacts and resolve through the same bundle-DID mapping as other API documents. Definition publication persists the pinned schema. |
| Dynamic protobuf | FOWF's old bundled protoc could not handle FDE's proto3 optional field and omitted standard imports | The adapter packages build-pinned modern protoc for both Linux architectures and the build host; standard schemas come from matching protobuf-java resources. Runtime compilation has a 30-second bound, no network/tool download, bounded returned diagnostics and temp cleanup. Legacy protoc remains test-scoped for an existing fixture only. |
| Actual FOWF integration | No deployed FDE workflow bundle | `fde/evaluate-decision/1.0.0` calls the real EvaluationService using a pinned ruleset version and tenant token. Its canonical evaluation.proto is included in the workflow bundle. Installer order remains migrations/identity/FOWF before FDE service and bundle publication. |

## Configuration and intentional contracts

- `DECISION_ENGINE_CACHE_MAXIMUM_TENANTS=256`: process lifetime admission cap. Reaching it rejects
  a new tenant with RESOURCE_EXHAUSTED; it does not evict an evaluator still owned by a request.
  Increase capacity/restart deliberately if this cap is unsuitable. This is not a global cluster limit.
- `DECISION_ENGINE_CACHE_RULES_PER_TENANT=100`: compiled rules LRU per tenant.
- `DECISION_ENGINE_EVALUATION_MAXIMUM_RULE_FIRINGS=10000`: an excess activation fails before executing its consequence;
  it is **not** a wall-clock sandbox. DRL contains executable Java and only trusted authors/admins
  should hold management capability. Arbitrary Java loops/I/O cannot be safely preempted in-process.
- FDE remains gRPC-only; it does not get an invented REST API or a separate Studio in this batch.
- New resource client `decisionengine` performs AuthZEN decisions. Separate
  `openworkflow-decision-evaluator` receives only the decision-evaluator role in each provisioned
  organization and the `decision-engine-api` audience. The platform administrator receives the
  decision-administrator role only for configured administrator tenant aliases.
- Secret Manager keys added by infrastructure are
  `<cluster>-platform-decision-engine-client-secret` and
  `<cluster>-platform-decision-engine-evaluator-client-secret`.
  ESO composes `decision-engine-identity-credentials/DECISION_ENGINE_CLIENT_SECRET` in namespace
  `decision-engine` and `openworkflow-decision-engine-credentials/OPENWORKFLOW_SERVICE_CLIENT_CREDENTIALS`
  in `forwardmeasure-openworkflow`. The latter contains operator-only token endpoint/client settings.
- Existing API callers that only send tenant-id must migrate to signed organization tokens with
  audience and capabilities. Agent OS's historical unsigned caller is **not activated or certified**
  by this FOWF integration. It will be rejected until its caller identity path is upgraded.
- Stateful append is atomic but evaluation is not a distributed transaction with Valkey. There is
  no duplicate-event/outcome lookup protocol. A lost response can mean the fact was appended. The
  FOWF gRPC adapter already marks ambiguous post-dispatch failures outcome-unknown; FOWF aborts
  without blind retry. Do not add a retry policy to the supplied workflow to hide those failures.
- Old shared fact-window keys are not migrated, because their tenant ownership cannot be inferred
  safely. Drain any pre-existing stateful use before moving to tenant-isolated windows.
- Admin cache/statistics operations are process-local and tenant-scoped, not cluster broadcasts.
- Static configured tenants are migrated by the ordered FDE migration Job. API-created FOWF tenants
  receive configured identity packs, but their FDE schema is **not automatically provisioned by the
  FOWF tenant API**. Before using FDE for such a tenant, include its alias in the migration inventory
  and rerun the supported migration/installer path. Membership grants alone do not provision tables.

## Verification performed (no test execution)

1. FDE full reactor, production and test sources:
   `scripts/build-bounded.sh -B test-compile -DskipTests -DskipITs -Dmaven.test.skip=false`.
   PASS, `/tmp/fde-rehabilitation-final-compile-2026-10-06.log`, 11.519 seconds.
2. FDE Quarkus, Spring, Micronaut and migration assemblies with dependencies:
   `scripts/build-bounded.sh -B -pl :decision-engine-quarkus,:decision-engine-spring,:decision-engine-micronaut,:decision-engine-database-migration-service -am package -DskipTests -DskipITs -Dmaven.test.skip=false -Dcontainer-image.build=false -Dcontainer-image.push=false`.
   PASS, `/tmp/fde-final-assemblies-package-2026-10-06.log`, 12.923 seconds. This checks Quarkus
   augmentation and packaging, not application startup or database transactions. An initial
   inheritance-based CDI bean failed augmentation; the composition-based implementation fixed it.
3. FOWF full 190-project reactor, both engines/backends and all frameworks, production/test compile:
   same test-compile command, PASS `/tmp/fowf-fde-final-compile-2026-10-06.log`, 65 seconds.
   This includes Claude's concurrent async-task test sources without changing them.
4. FDE enabled/disabled Helmfile builds; FOWF enabled build with embedded values; synthetic identity
   rendering; actual local service and bundle chart renders. Outputs are
   `/tmp/fde-helmfile-build-2026-10-06.yaml`, `/tmp/fde-disabled-helmfile-build-2026-10-06.yaml`,
   `/tmp/fowf-fde-helmfile-build-2026-10-06.yaml`, `/tmp/fde-identity-manifests-2026-10-06.yaml`,
   `/tmp/fde-chart-render-2026-10-06.yaml`, `/tmp/fde-bundle-chart-render-2026-10-06.yaml`.
   The identity fixture uses synthetic values; it does not read live credentials or prove ESO sync.
   Inspection confirms composed Secret keys, adapter references, probes and writable volume.
5. Evaluation workflow passes the repository's Serverless Workflow JSON schema. Canonical FDE
   evaluation.proto compiles with the packaged modern compiler and matching standard imports into
   `/tmp/fde-protobuf-compile-2026-10-06/evaluation.pb` (1,487 bytes).
6. Source whitespace checks and `tofu fmt -check terraform/gcp/main.tf` pass. No Terraform apply,
   cluster command, functional test, image build or push was performed.

Older logs named `fde-full-compile-final` predate the last edits; use the evidence listed here.
A successful compiler, render or package command must not be recorded as a passing behavior test.

## Exact operator build/deployment scope

For the current Quarkus + Kafka Streams installation this batch changes **five images**:

| Repository | Image (`forwardmeasure/`, tag `1.1.0`) |
|---|---|
| FDE | `decision-engine-quarkus` |
| FDE | `decision-engine-database-migration-service` |
| FOWF | `openworkflow-operation-adapter-kafka-streams-quarkus` |
| FOWF | `openworkflow-definition-management-quarkus` |
| FOWF | `openworkflow-workflow-publisher` |

From FDE:

```bash
set -o pipefail
scripts/build-bounded.sh -B \
  -pl :decision-engine-quarkus,:decision-engine-database-migration-service -am \
  package -Pcontainer-image -Dcontainer-image.push=true \
  -DskipTests -DskipITs -Dmaven.test.skip=true \
  2>&1 | tee /tmp/fde-images-2026-10-06.log
```

From FOWF:

```bash
set -o pipefail
scripts/build-bounded.sh -B \
  -pl :openworkflow-operation-adapter-kafka-streams-quarkus,:openworkflow-definition-management-quarkus-service,:openworkflow-workflow-publisher -am \
  package -Pcontainer-image -Dcontainer-image.push=true \
  -DskipTests -DskipITs -Dmaven.test.skip=true \
  2>&1 | tee /tmp/fowf-fde-images-2026-10-06.log
```

`-am` builds library dependencies, not unrelated deployment images. The bounded wrappers share
one lock; run these sequentially. No FEI, FDS, engine, execution-management or Studio image needs
rebuilding for this FDE batch. If the earlier async-task migration repair has not yet been built,
also include `:openworkflow-migrations,:openworkflow-tenant-administration-quarkus-service` in the
FOWF module list: those are **two additional images from the separate earlier repair**.
For another framework/engine, substitute that FDE server, definition-management and adapter image;
the migration and publisher images are framework-neutral.

Source chart version for decision-engine is now **0.1.10**, matching the operator's Helmfile pin.
Pre-deployment reinspection on 2026-10-06 found that the actual local chart still declared 0.1.8
and lacked the probe/temp-storage changes described above. The local source is now corrected:
numeric gRPC probes with explicit readiness/liveness service names, startup probe, writable `/tmp`,
filesystem group, gRPC port environment variable, and optional migration tenant/digest wiring.
`helm lint` and synthetic `helm template` checks passed for the default service and an enabled
migration with a non-default port and pinned migration image. Render evidence:
`/tmp/fde-chart-predeploy-2026-10-06.yaml` and
`/tmp/fde-chart-predeploy-migration-2026-10-06.yaml`. No cluster validation or chart publication
was performed. These checks supersede the earlier chart-source assertions for this checkout.

The workflow-bundle chart's previously recorded version is **0.1.5**.
Publish these with the normal chart workflow before any consumer switches to their remote chart
artifacts. The current product Helmfiles use these local source paths; chart version pins do not
make an unpublished remote package appear. Existing FDS/FEI workflow bundles remain compatible.

Apply infrastructure using the user's Makefile:

```bash
set -o pipefail
make -C /home/pn/Documents/code/forwardmeasure/openworkflow-k8s-setup infra-apply \
  2>&1 | tee /tmp/fde-infrastructure-2026-10-06.log
```

Then run the normal installer with the FDE opt-in exported for the entire command:

```bash
set -o pipefail
FORWARDMEASURE_ENABLE_FDE=true \
  /home/pn/Documents/code/forwardmeasure/forwardmeasure-platform/deploy/install-platform.sh \
  gcp-openworkflow-prod 2>&1 | tee /tmp/fowf-fds-fei-fde-install-2026-10-06.log
```

This creates/reconciles identity, migrates before application rollout, refreshes selected image
digests and publishes the bundle after the FDE service is ready. No separate ad hoc engine upgrade
is required. The source renderer does not establish those live steps have succeeded.

Installer follow-up, 2026-10-06: the retry log stopped after resolving the FDE server image,
before any Helm sync. The workflow environment requires `OPENWORKFLOW_VERSION`; the earlier command
omitted it and the environment-value helper suppressed Helmfile's error output. With the variable
unset the same failure was reproduced; explicitly setting `1.1.0` and enabling FDE renders
successfully and reports the FDE selection as true. Both platform/workflow value helpers now
preserve render errors, and the umbrella installer checks workflow environment inputs before
registry lookups. Shell syntax checked; no deployment executed by Codex for this diagnosis.

Subsequent cleanup approved by the user: the mandatory `OPENWORKFLOW_VERSION` overrides have now
been removed, so the installer command above intentionally omits that variable. Committed image
inventories select the tags; digest resolution is unchanged. Unused project-ID inputs and obsolete
Cloud SQL/service-account exports were also removed. FDE enablement remains an explicit choice.
All seven FOWF environment builds succeeded without version/project exports, supplying only the
standalone production profile's genuine endpoint/identity inputs where needed. This supersedes
the temporary environment-variable workaround in the preceding incident record.

## Claude verification instructions

**Live deployment defect and source repair, 2026-10-06:** the umbrella install's
`decision-engine-migrations` Job repeatedly exited with `DECISION_ENGINE_TENANTS is required`.
Its FOWF-owned release omitted this required application environment variable. The release now
derives the `alias:display-name` CSV from `.Values.tenants`, matching the other product migrators,
and rejects an empty tenant list at render time. No operator shell export is needed. The actual
GCP release rendered through the pinned java-microservice chart with
`DECISION_ENGINE_TENANTS: "lux:Lux Tenant"`; manifest evidence is
`/tmp/fde-migration-tenant-render-2026-10-06.yaml`. No image rebuild or chart publication is required:
the fix is in `forwardmeasure-openworkflow/deploy/helmfile/helmfiles/migrations.yaml.gotmpl`.
The retry3 deployment subsequently completed the FDE migration Job in 11 seconds (FOWF in 12
seconds and FEI in 14 seconds); the later FDE server failure was the health-protocol defect above.
Include the real migration Job's required environment contract
in deployment coverage, rather than testing only the optional init-container migration path.

Follow the [shared testing specification](../../../forwardmeasure-openworkflow/docs/rehabilitation/claude-testing-instructions-2026-10-06.md).
Tests/fixtures/test-scoped configuration only; production defects go to Codex. Compile with the
bounded wrapper. Do not execute tests until the user authorizes execution. Do not retain old
conformance expectations that unsigned tenant metadata is accepted by production bindings.
Use real PostgreSQL, Valkey, Keycloak and gRPC, plus the shipped chart/migrator and k3s startup
fixtures. Exercise Quarkus, Spring and Micronaut; real FOWF integration covers Kafka Streams,
Pekko/PostgreSQL and Pekko/Cassandra with the same workflow contract.

Required cases and independent expected behavior:

1. Two actual organizations with the same ruleset name, version and session key; deliberately
   different DRL outcomes and facts. Concurrent requests must never cross rules or fact windows.
2. Missing/expired/wrong-issuer/wrong-audience/invalid-signature JWT; non-member, conflicting
   tenant metadata, top-level role spoof and evaluator attempting manage/admin. All deny without
   database mutation, cache creation or Valkey append. Exercise actual CDI proxies and gRPC thread hops.
3. Warm a cache, deactivate tenant, then evaluate and call cache administration. Fresh requests
   must deny even though compiled state exists. Bound authorization decision-cache revocation to
   its configured one-second TTL; registry ACTIVE checks are never process-lifetime cached.
4. Real restricted-role create/activate/get/list/delete through each packaged framework. Inject
   commit failure and verify no successful gRPC response precedes commit. Race first creation,
   activation and deletion across two processes; preserve exactly one active version and unique
   version identities. Delete the largest version and prove it is never reused.
5. Migrate a populated old database with the shipped migrator and restricted runtime role;
   preserve original rows and seed counters above existing maxima. Inspect table privileges.
   Repeat migrations; no checksum edits or ad hoc schema creation in the fixture.
6. Cache hit/miss/LRU/unload/clear/validation: prove module cleanup and correct outcomes, including
   concurrent in-flight evaluation during eviction/clear. Exercise configured capacity exhaustion
   and firing limit. Do not claim firing limits interrupt arbitrary Java consequences.
7. Stateful window size, order and TTL with real Valkey; restarts/script eviction and two clients
   sharing a key. Inject an append-success/response-loss failure. No invented exactly-once claim.
8. Through a real FOWF engine, publish the actual `.proto` bundle to real Apicurio, resolve/pin it,
   then execute the actual generic gRPC adapter with actual service tokens. Verify optional
   ruleset version, Struct/null values, fired rules, facts considered, explicit version pinning,
   and tenant-specific concurrent tokens. Clear runtime network access to Maven to prove compiler
   packaging, rather than a developer machine's installed protoc, supplies the tool.
9. Kill the server/adapter after dispatch or drop the response following stateful append. The
   workflow must terminate outcome-unknown without repeating the action; status/history must
   retain diagnostic context. Check ordinary explicit business failures separately.
10. Render and boot all three server frameworks with read-only root filesystems and no existing
    state. Check numeric readiness/liveness probes, dependency outage/recovery, migration order,
    repeated install, interrupted secret rotation, exact composed Secret keys and both embedded
    and platform Valkey authentication. Probe readiness does not expose tenant or credential data.
11. Reject unauthorized direct service calls from another namespace even though ClusterIP is
    reachable. Legacy unsigned Agent OS calls should fail, not silently gain a trusted bypass.
12. API-created tenants: identity pack is applied, but FDE is unavailable until its schema is
    provisioned through the documented migration inventory. Assert this boundary explicitly;
    do not manually fabricate tables and label that automatic onboarding.

For each case record source revision, image digest where relevant, fixture versions, written /
compiled / executed status, command, exit and durable log. A packaged image is not runtime evidence.
Do not hide source defects by changing expected results or substituting trusting tenant resolvers.
