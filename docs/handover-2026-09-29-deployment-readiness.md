# FDE (forwardmeasure-decision-engine) handover — deployment readiness, 2026-09-29

**Written for:** a fresh Claude Code session/agent picking up work on this repo with no memory of
prior sessions. Read this first, then `docs/IMPLEMENTATION-SPEC.md` (the authoritative architecture
spec — long, but every claim in this handover traces back to it or to direct code/config reads done
2026-09-29).

**Do not treat anything below as done just because it's described in detail.** Several items here are
explicitly *not* done — flagged as such — and are the actual reason this document exists.

---

## Update (later on 2026-09-29)

- **§4.1 chart capability built**: `decision-engine-helm-chart` 0.1.5 (not yet published) has a
  `cloudSqlProxy` block with the same values contract as `java-microservice-helm-chart`
  (Secret-key-only instance connection name, `<release>-cloudsql` / `db-cloud-sql-instance`),
  rendered as a native sidecar so it is up before the optional migration init container. The same
  change fixed a real bug: `database-migration` was a regular container (restart-looping), now an
  init container. `deploy/helmfile` wires it (`cloudSqlProxy.enabled: true` in
  `gcp-openworkflow-prod`). Still blocked on the `<release>-cloudsql` Secret (same unresolved gap
  as forwardmeasure-openworkflow's own releases) and a Workload Identity GSA mapping.
- **Single owner**: `forwardmeasure-platform` no longer deploys its own duplicate `decision-engine`
  release (`helmfiles/80-decision-engine.yaml.gotmpl`, removed). This repository's helmfile is the
  only owner.
- **Shared values**: cross-repository values (domain, gateway, endpoints, shared namespaces, shared
  chart/image pins, platform-delivered Secret names) now come from
  `forwardmeasure-platform/deploy/helmfile/shared/`, loaded directly by this repository's helmfile
  (`.Values.platform.*`). The synced `environments/platform-endpoints.yaml.gotmpl` copy is gone.

## 1. What this repo is, in one paragraph

A general-purpose rules/decision engine (Drools-backed), gRPC-only (no REST/JAX-RS anywhere — this is
a hard constraint, `IMPLEMENTATION-SPEC.md` Section 13 item 2), built as three framework bindings
(Quarkus/Spring/Micronaut) over one shared, framework-neutral core. Two capabilities: **Evaluation**
(fire a named ruleset's rules against an input document, stateful rulesets also read/write a
Valkey-backed fact window) and **Management** (CRUD for ruleset definitions/versions). Real
multi-tenancy is database-per-tenant, schema-per-domain (`decision_intelligence` schema), matching the
same model `forwardmeasure-openworkflow`/`forwardmeasure-entity-intelligence` already use for their own
schemas in the same per-tenant database. Full architecture diagram: `IMPLEMENTATION-SPEC.md` Section 4.

**Real, current caller**: `forwardmeasure-agent-os`, over gRPC, having already resolved tenant identity
upstream through its own verified flow. Decision-engine itself never calls out to
`forwardmeasure-openworkflow`'s execution-management/definition-management API, and dispatches no
Kubernetes Jobs of its own (confirmed by direct grep, 2026-09-29 — this repo is a *callee*, not a
caller, unlike `forwardmeasure-entity-intelligence`'s ingestion/screening services).

**Trust model, as currently decided (Section 13 item 8, "Resolved")**: internal-only, ClusterIP,
reached only from inside the cluster, no mTLS, no per-caller auth token. Tenant identity for a call is
resolved by `TrustingMetadataTenantResolver` (the default) — it trusts a plain, unauthenticated
`tenant-id` gRPC metadata field, which is safe *only* because the real trust boundary is "which pods
can reach this port," and the one real caller (`agent-os`) already verified the caller's identity
before ever reaching decision-engine. A second resolver, `VerifiedJwtTenantResolver` (real Keycloak JWT
verification via JWKS), exists in code but is **not wired on by default** and must not be enabled
without a fresh, explicit decision — see §5 below.

---

## 2. What's real and committed (verified 2026-09-29, not assumed)

- Full module tree builds: `decision-engine-domain` (no I/O), `decision-engine-core`
  (`DroolsRuleEvaluator`, tenant-scoped via `TenantScopedRuleEvaluators`), `decision-engine-jpa`
  (`Ruleset` entity/repository, Postgres), `decision-engine-fact-window` (Valkey-backed
  `FactWindowStore`), `decision-engine-grpc` (framework-neutral `EvaluationServiceImpl`/
  `ManagementServiceImpl`, `TenantContextServerInterceptor`), 3 framework bindings, conformance +
  architecture test modules. Real commit history (`git log`, 10 real commits, latest
  `bf94b52 Bumped version to 1.1.0`), not a stub repo.
- `decision-engine-database-migration-service` → `RulesetMigrationsMain.java` — reuses the shared
  `OpenWorkflowTenantMigrator` library exactly like fowf's and fei's own migration Jobs, targeting the
  `decision_intelligence` `FunctionalSchema`. Has a real `src/main/docker/Dockerfile.jvm`, and has been
  built as a container image locally at least once (`target/docker/.../decision-engine-database-
  migration-service/1.1.0/...` present in the working tree).
- The published `decision-engine` Helm chart source (`helm-charts/charts/decision-engine-helm-chart/
  helm-chart-sources/`) — real `Chart.yaml`/`_helpers.tpl`/`values.yaml`/`templates/{deployment,
  service,serviceaccount}.yaml`. Deliberately has **no** `embedded`/`external` Valkey-mode concept of
  its own — that toggle lives one layer up in this repo's own `deploy/helmfile/`
  (`IMPLEMENTATION-SPEC.md` §9.1, a documented, deliberate boundary — do not add a `mode` field to the
  chart's own `values.yaml`).
- `deploy/helmfile/` — `helmfile.yaml.gotmpl` (2 releases: `decision-engine-valkey`, conditionally
  installed when `factWindow.valkey.mode == "embedded"`; `decision-engine`, the server itself),
  `environments/{base.yaml.gotmpl,chart-versions.yaml}`, `releases/decision-engine/base.yaml.gotmpl`,
  `install.sh`. This whole tree is **uncommitted** as of this handover (`git status --short` shows it
  all as modified/untracked) — a concurrent session built and wired it into
  `forwardmeasure-platform`'s own `install-platform.sh` on 2026-09-29, the same day this handover was
  written. Read the real current state with `git status`/`git diff`, don't assume it matches whatever a
  later commit history shows.

---

## 3. What was fixed today (2026-09-29) — real, verified, but only closes one specific failure mode

**The bug**: `forwardmeasure-platform/deploy/install-platform.sh` chains 5 products' own `install.sh`
scripts, `gcp-openworkflow-prod` (or whatever environment name is passed) run through to each,
decision-engine last. Decision-engine's own `deploy/helmfile/helmfile.yaml.gotmpl` declared only a
`base` environment — `helmfile --environment gcp-openworkflow-prod` would fail immediately with an
undeclared-environment error, **aborting the entire 5-step install-platform.sh chain at its last step**
every time it ran against that environment name.

**The fix**:
- Added a `gcp-openworkflow-prod` entry to `helmfile.yaml.gotmpl`'s own `environments:` map, layering
  `environments/chart-versions.yaml` → `environments/base.yaml.gotmpl` →
  `environments/gcp-openworkflow-prod.yaml.gotmpl` (new file) — the same layering convention
  `forwardmeasure-entity-intelligence`'s and `forwardmeasure-data-streaming`'s own `helmfile.yaml.gotmpl`
  already use.
- The new `environments/gcp-openworkflow-prod.yaml.gotmpl` uses **real values copied verbatim from
  `forwardmeasure-platform/deploy/helmfile/environments/base.yaml.gotmpl`'s own `decisionEngine:`
  block** (that file already had the intended real shape — `database.url`, `database.credentialsSecret`,
  `factWindow.valkey.host` — just never pulled into this repo). Not invented.
- Added a new `installed: {{ dig "decisionEngine" "enabled" true .Values }}` gate on the
  `decision-engine` release itself (it had none before — `IMPLEMENTATION-SPEC.md` §9.2's own template
  only gates `decision-engine-valkey`, not the server). Defaults `true` (base/local/dev unaffected),
  overridden `false` in the new prod overlay.
- Set `decisionEngine.enabled: false` in the prod overlay, matching `forwardmeasure-platform`'s own
  exact stated reason (quoted from that file): *"Disabled until platform infrastructure provisions its
  database, runtime credentials, and namespace-scoped Valkey credentials. The release wiring is ready;
  enabling it without those prerequisites would create a non-functional pod."*

**Verified via real `helmfile build`/`list`** (not a live cluster — no GCP/kubectl credentials were
available in that session): `gcp-openworkflow-prod` now resolves cleanly (`EXIT=0`, previously would
have errored outright), both releases correctly render `INSTALLED: false`; `base` re-confirmed
unaffected (`INSTALLED: true`, same as before the fix).

**What this fix does NOT do**: it does not make decision-engine's runtime service actually deployable
in `gcp-openworkflow-prod`. `decisionEngine.enabled` stays `false` because the real prerequisites
genuinely don't exist yet — see §4. This fix's whole job was "stop `install-platform.sh` from
hard-aborting," which it does; it deliberately does not pretend the deeper gaps are closed too.

---

## 4. Real, confirmed, still-open gaps — the actual reason to hand this off as its own thread

Ordered roughly by "blocks everything downstream" → "narrower."

### 4.1 No Cloud SQL Auth Proxy sidecar in the published chart at all

Confirmed by direct read of `helm-charts/charts/decision-engine-helm-chart/helm-chart-sources/
templates/deployment.yaml` and `values.yaml`: zero `cloudSqlProxy` key anywhere. Every other product in
this cluster (`forwardmeasure-openworkflow`, and — via `java-microservice-helm-chart` —
`forwardmeasure-entity-intelligence`/`forwardmeasure-data-streaming`) reaches Postgres through a
per-pod Cloud SQL Auth Proxy sidecar listening on `localhost:5432`, which is exactly the URL shape
`gcp-openworkflow-prod.yaml.gotmpl` now declares (`jdbc:postgresql://localhost:5432/decision_engine`).
**That URL is not reachable today** — nothing in the pod would ever listen on `localhost:5432` without
this sidecar existing.

The real, proven reference implementation to port from is `java-microservice-helm-chart`'s own
`cloudSqlProxySidecar`/`cloudSqlProxyNativeSidecar`/`cloudSqlProxyImageRef`/`cloudSqlProxySecretName`
named templates (`helm-charts/charts/java-microservice-helm-chart/helm-chart-sources/templates/
_helpers.tpl`, roughly lines 32–55 and 1019–1055 as of this handover — grep for `cloudSqlProxy` in that
file, it's the fastest way to find the current line numbers). Decision-engine's chart is much simpler
(one Deployment, no knative/job/scaledJob modes, no per-service `services:` map) — a direct,
non-parameterized port of just the plain-Deployment sidecar shape (not the "native sidecar as
initContainer" variant, which only matters for Job-mode workloads) is enough. Real, already-pinned
image reference to reuse: `gcr.io/cloud-sql-connectors/cloud-sql-proxy:2.25.3`
(`sha256:821e5050a77697269399cadcaf5fa55322411dd1922020ea9729fd4ad174810a` — confirmed in
`forwardmeasure-openworkflow/deploy/helmfile/environments/image-versions.yaml`, shared across every
product that already uses this sidecar).

**Open design question, not yet resolved**: `java-microservice-helm-chart`'s own sidecar reads the
Cloud SQL instance connection name from a `secretKeyRef` (key `db-cloud-sql-instance` by convention) —
a deliberate decision from an earlier session (*"Cloud SQL `instanceConnectionName` stays Secret-key-
only — no chart change; three duplicate template call sites made a plain-value alternative not worth
the complexity"*). Whether decision-engine's own sidecar should follow the same Secret-key-only
convention, or whether its single-Deployment, single-database shape makes a plain value simpler and
safer, is a real call to make when this gets built — not decided here.

### 4.2 `decision_engine` database not yet in Terraform

Confirmed by direct grep, 2026-09-29: `grep -rn "decision_engine\|decisionEngine\|decision-engine"
k8s-infrastructure/opentofu/gcp/*.tf` (in `forwardmeasure-platform`) returns **zero hits**. The
`cloudsql_databases` map that already provisions `entity_intelligence`/`keycloak`/`openworkflow`/
`superset`/`platform` on the shared `openworkflow-prod-postgres` Cloud SQL instance has no
`decision_engine` entry. There is no real Cloud SQL database for this product to connect to yet,
regardless of anything else being fixed. Adding it is real, standard Terraform work — one more map
entry, following the exact pattern the other 5 databases already use — but it's infrastructure work in
`forwardmeasure-platform`, a different repo, and needs whoever owns `k8s-infrastructure/opentofu/gcp`
to actually apply it against real Terraform state.

### 4.3 Neither credentials Secret has a real source

- `decision-engine-database-credentials` (the runtime DB credential) — no `platform-secrets` entry
  anywhere (`forwardmeasure-platform/deploy/helmfile/releases/platform-secrets/gcp/base.yaml.gotmpl`
  only has `dataStreamingCredentialsSecret`/`entityIntelligenceCredentialsSecret` — no decision-engine
  equivalent). Depends on §4.2 existing first (can't have a runtime credential for a database that
  doesn't exist).
- `decision-engine-valkey-auth` — same situation, no provisioning found anywhere. Depends on whichever
  shared/external Valkey instance `factWindow.valkey.external.host: valkey.valkey.svc.cluster.local`
  actually is (confirmed that Service name exists in `forwardmeasure-platform`'s own
  `namespaces.valkey: valkey` entry, but nothing was checked about whether *this product's own*
  auth credential against that shared instance has been provisioned).

**Real precedent to follow once §4.2 is done**: `entity-intelligence-credentials`/
`data-streaming-credentials` (in `forwardmeasure-entity-intelligence`/`forwardmeasure-data-streaming`
respectively) are the two most recent, real, working examples of a product provisioning its own runtime
credentials via a small bootstrap Job — though those are Keycloak-client-secret patterns specifically;
a Postgres runtime-credential delivery is architecturally closer to how `openworkflow-migrations`
itself creates a runtime role + password and that becomes the thing `platform-secrets` (or a
product-owned equivalent) needs to deliver into a Secret. Look at how `entity-intelligence-migrations`'
own runtime-role-creation flow gets its resulting credential into a Secret consumable by
`entity-intelligence-services` for the closest real template — same shape decision-engine will need for
`decision-engine-database-migration-service` → `decision-engine` (server).

### 4.4 `decision-engine-database-migration-service` has zero deployment wiring

Confirmed 2026-09-29: `RulesetMigrationsMain.java` is real, compiles, has a real Dockerfile, and has
been built as a local image at least once — but there is **no Helm release for it anywhere** in this
repo's `deploy/helmfile/releases/` (only `decision-engine`/`decision-engine-valkey` exist), and it is
**not referenced anywhere in `forwardmeasure-platform`'s own `install-platform.sh`**. This is the same
"designed, built, never wired" pattern that showed up repeatedly elsewhere in this platform (e.g.
`entity-intelligence-workflows` before it got a real Helm release). Without this, even once §4.1–4.3 are
resolved and `decisionEngine.enabled` flips to `true`, the `decision_intelligence` schema itself would
never get created/migrated in any real tenant's database — the server would come up and fail on its
first real query.

Real templates to mirror for building this release: `openworkflow-migrations` (fowf's own repo) for the
Job/hook shape (`helm.sh/hook: post-install,post-upgrade`), or `entity-intelligence-migrations` (fei's
own repo) for the closer sibling-product pattern (same `OpenWorkflowTenantMigrator` library, same
per-tenant-schema provisioning shape, just targeting `FunctionalSchema.DECISION_INTELLIGENCE` instead of
`ENTITY_INTELLIGENCE`).

### 4.5 The standalone-deployment / `VerifiedJwtTenantResolver` decision has not been made

`IMPLEMENTATION-SPEC.md` §13 item 8 is explicit: enabling `VerifiedJwtTenantResolver` (or building any
further per-caller authorization) needs "a fresh, equally explicit decision at the point a standalone
deployment actually happens." That decision has not been made. **Do not flip this on as a side effect
of fixing §4.1–4.4** — it's an intentionally separate, currently-closed door. If a future need for
decision-engine to be reachable from outside its current ClusterIP-only boundary comes up, that's the
trigger to revisit this, not "we're deploying to prod now."

One related, not-yet-scoped design note for whenever that decision does get made: `VerifiedJwtTenantResolver`
verifies against an *existing* organization-client-id (conceptually fowf's own client, the one every
other product's tenant-claim verification already points at) rather than minting its own new OIDC
client — so this almost certainly does **not** need an `entity-intelligence-credentials`-style
"provision a new Keycloak client" bootstrap Job the way FEI/FDS needed. Confirm this against the real
`VerifiedJwtTenantResolver` source (`decision-engine-grpc`'s `tenancy` package) before assuming either
way.

---

## 5. Suggested order of attack, if picking this up for real

Not a mandate — a reasonable sequencing given the dependency chain above:

1. **§4.2 (Terraform)** — blocks everything else; smallest, most mechanical piece (one map entry,
   following an established pattern exactly). Needs real `terraform apply` access, so confirm who
   actually owns/runs that before starting.
2. **§4.4 (migrations release)** — can be built and verified via `helmfile build`/`template` entirely
   without §4.2/4.3 being done yet (same as this session's own §3 fix pattern — build the wiring, gate
   it off, verify structurally). Real, valuable, independently completable work.
3. **§4.3 (credential delivery)** — depends on §4.2 existing for real; design the delivery shape once
   the real database/role exists to deliver credentials *for*.
4. **§4.1 (Cloud SQL Proxy sidecar in the chart)** — can be built and verified via `helm template`
   independently of §4.2/4.3, but has no real value to flip on (`decisionEngine.enabled: true`) until
   they're both done too. Consider building the chart capability now (real, testable, no live
   dependency) even if the environment gate stays `false` until the rest lands.
5. **Flip `decisionEngine.enabled: true`** in `gcp-openworkflow-prod.yaml.gotmpl` only once §4.1–4.4 are
   all genuinely done and live-verified — not before. This is a real production database connection;
   don't flip it speculatively.
6. **§4.5** stays closed unless a real, separate business need for standalone/external reachability
   comes up — don't open it opportunistically while doing the above.

---

## 6. Quick file map

```
forwardmeasure-decision-engine/
  docs/IMPLEMENTATION-SPEC.md          — authoritative architecture spec, read before changing anything
  docs/handover-2026-09-29-...md       — this file
  decision-engine-core/                — DroolsRuleEvaluator, TenantScopedRuleEvaluators
  decision-engine-domain/              — RuleEvaluator port, value types, no I/O
  decision-engine-jpa/                 — Ruleset entity/repository (Postgres)
  decision-engine-fact-window/         — ValkeyFactWindowStore (stateful-ruleset fact history)
  decision-engine-grpc/                — EvaluationServiceImpl, ManagementServiceImpl,
                                          TenantContextServerInterceptor, tenancy/ (both resolvers)
  decision-engine-framework-bindings/  — quarkus/spring/micronaut, wiring only
  decision-engine-deployments/
    database-migration-service/        — RulesetMigrationsMain, real Dockerfile, NO Helm release (§4.4)
  deploy/helmfile/
    helmfile.yaml.gotmpl               — 2 releases: decision-engine-valkey, decision-engine
    environments/base.yaml.gotmpl      — dev/local defaults (Postgres/Valkey embedded-friendly)
    environments/gcp-openworkflow-prod.yaml.gotmpl  — NEW 2026-09-29, real values, enabled: false
    environments/chart-versions.yaml
    releases/decision-engine/base.yaml.gotmpl
    install.sh

forwardmeasure-platform/ (sibling repo)
  deploy/install-platform.sh           — chains all 5 products' install.sh, decision-engine last
  deploy/helmfile/environments/base.yaml.gotmpl  — decisionEngine: {enabled:false, database:..., ...}
                                          the ALREADY-DECLARED real values this repo's own new
                                          gcp-openworkflow-prod.yaml.gotmpl mirrors
  k8s-infrastructure/opentofu/gcp/*.tf — where §4.2's Terraform entry needs to go

helm-charts/ (sibling repo)
  charts/decision-engine-helm-chart/helm-chart-sources/   — the published chart, needs §4.1's sidecar
  charts/java-microservice-helm-chart/helm-chart-sources/templates/_helpers.tpl
                                        — real, working Cloud SQL Proxy sidecar to port from (§4.1)

forwardmeasure-entity-intelligence/ (sibling repo, closest real precedent for §4.3/§4.4)
  deploy/helmfile/releases/entity-intelligence-credentials/   — credential-bootstrap Job precedent
  (its own migrations release, wired into forwardmeasure-openworkflow's own deploy/helmfile)
                                        — per-tenant-schema migration Job precedent for §4.4
```

---

## 7. Hard constraints to not violate while doing any of the above (from `IMPLEMENTATION-SPEC.md` §13)

- No REST/JAX-RS surface, anywhere, ever. gRPC only.
- No `drools-quarkus`/KIE Spring Boot starter/hand-rolled Micronaut-Drools glue — Drools access goes
  through `decision-engine-core`'s plain `kie-api` usage only, for all three frameworks.
- No Infinispan or other distributed-cache technology for the fact window — Valkey only, already
  decided and justified in the spec.
- No sticky/session-affine gRPC routing — the fact window exists specifically so this is never needed.
- No agent-os awareness in this repo whatsoever, even though `forwardmeasure-agent-os` is the real
  caller — zero build/test/deploy-time knowledge of that repo or OPA.
- Tenant isolation is physical (database-per-tenant), never a `tenant_id` filter column on a shared
  schema.
