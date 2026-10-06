# FDE (forwardmeasure-decision-engine) handover — deployment readiness

**Superseding implementation, 2026-10-06:** read the
[current FDE rehabilitation/integration handover](rehabilitation/fde-rehabilitation-handover-2026-10-06.md).
Production identity, tenancy, persistence, runtime and FOWF integration repairs are implemented and
compiled; framework assemblies package. Runtime verification and operator rollout remain pending.
Unsigned tenant metadata is no longer accepted by production bindings. FDE remains opt-in until the
operator applies the new infrastructure and deploys the specified images. The September statements
below are historical, including their trust model and claims that FDE was already enabled.

**Written for:** a fresh Claude Code session/agent picking up work on this repo with no memory of
prior sessions. Read this first, then `docs/IMPLEMENTATION-SPEC.md` (the authoritative architecture
spec). Every claim below was checked against code/config on 2026-09-29; re-check before acting.

**Status (end of 2026-09-29):** every prerequisite is now provided by config and
`decisionEngine.enabled` is **true** on the GCP clusters. What remains is live: one Terraform apply,
then the next `install-platform.sh` run, then live verification (§4.6). Nothing has been deployed
from this work yet. Rewritten
2026-09-29 (later the same day) - an earlier version of this document wrongly said decision-engine
needed its own `decision_engine` database and had no migrations release; both are corrected below.

---

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

## 2. Where the data lives (the part earlier versions got wrong)

- **No database of decision-engine's own.** Ruleset data lives in each tenant's own database
  (`forwardmeasure_<alias>`, e.g. `forwardmeasure_lux`) in the `decision_intelligence` schema, next
  to `openworkflow` and `entity_intelligence` (`IMPLEMENTATION-SPEC.md` §7.3, §13 item 3).
- **The server's one direct connection is the platform control-plane database**
  (`forwardmeasure_control_plane`, published as `platform.databases.controlPlane` in
  forwardmeasure-platform's shared layer). It
  holds `tenant_registry`; on every gRPC call `TenantDatabaseResolver` (forwardmeasure-jpa) looks up
  the caller's tenant database there over the default datasource (`DECISION_ENGINE_CONTROL_PLANE_DATABASE_URL`),
  then `TenantDataSourceRegistry` connects to that tenant's database on the same host/port.
  `deploy/helmfile/releases/decision-engine/base.yaml.gotmpl` builds that URL
  (`jdbc:postgresql://localhost:5432/forwardmeasure_control_plane` - through the Cloud SQL Auth
  Proxy on GCP).
- **Runtime role `decision_engine`**, created/rotated by the migrations Job below. It owns nothing;
  it gets DML on `decision_intelligence` in each tenant database and `SELECT` on
  `tenant_registry` (`OpenWorkflowTenantMigrator.grantTenantRegistryRead()`, called from
  `RulesetMigrationsMain` - added 2026-09-29; forwardmeasure-openworkflow and
  forwardmeasure-entity-intelligence make the same call for their own runtime roles).
- **Migrations run in forwardmeasure-openworkflow's deployment, not here.** Its `migrations` stage
  has a `decision-engine-migrations` release (`deploy/helmfile/helmfiles/migrations.yaml.gotmpl`)
  running this repo's `decision-engine-database-migration-service` image
  (`RulesetMigrationsMain`) as fowf's migration admin against the control-plane database, for every tenant in the shared tenant list - the same pattern as
  `entity-intelligence-migrations`. It is installed on gcp-openworkflow-prod today.

## 3. What's in place (verified 2026-09-29)

- Code: full module tree (domain, core, jpa, fact-window, grpc, 3 framework bindings, conformance and
  architecture tests, migration service).
- Chart: `decision-engine-helm-chart` **0.1.6** (published) - Cloud SQL Auth Proxy as a native
  sidecar (Secret-key-only instance name `<release>-cloudsql`/`db-cloud-sql-instance`, same contract
  as `java-microservice-helm-chart`); the optional in-pod migration is an init container.
- Deployment: this repo's `deploy/helmfile` is the only owner of the `decision-engine` release
  (forwardmeasure-platform's duplicate was removed). Environments `base`, `gcp-openworkflow-prod`
  and `gcp-greenfield-example`; both GCP environments load `environments/gcp-platform-cluster.yaml.gotmpl`
  plus forwardmeasure-platform's shared layer (`deploy/helmfile/shared/`), which supplies
  namespace, Valkey host, image-pull Secret, the control-plane database name and cluster facts.
  Wired into `install-platform.sh` (runs last) and into platform CI's `helmfile-validate` job.

## 4. Prerequisites - all closed in config (2026-09-29)

### 4.1 The migrations Job's runtime password - closed
fowf's `decision-engine-migrations` reads `DECISION_ENGINE_RUNTIME_DATABASE_PASSWORD` from fowf's
credentials Secret (`openworkflow-identity-credentials`). forwardmeasure-platform's platform-secrets
now delivers it (remote key `platform-decision-engine-runtime-database-password`, set in both GCP
environments' `secretStore.remoteKeys`, present in each environment's fake-store credentials file -
the real prod value only in the gitignored `openworkflow-prod.credentials.yaml.gotmpl`). The same fix
corrected fei's `ENTITY_INTELLIGENCE_RUNTIME_DATABASE_PASSWORD`. The role name `decision_engine` is
now a shared contract (`platform.databases.runtimeRoles.decisionEngine`) read by both that Job and
platform-secrets.

### 4.2 The server's own credentials - closed
platform-secrets delivers into the `decision-engine` namespace:
- `decision-engine-database-credentials` (`platform.secrets.decisionEngineDatabase`): `username`
  (the shared role name), `password` (same remote key as 4.1), and `db-cloud-sql-instance`
  (from the shared cluster file) - composed with External Secrets' `template`.
- `decision-engine-valkey-auth` (`platform.secrets.decisionEngineValkey`): the shared Valkey's
  password (its single `default` ACL user - the same one superset uses).
- `docker-io-credentials` (image pulls) - `decision-engine` and `entity-intelligence` were missing
  from its namespace list.

### 4.3 Cloud SQL Auth Proxy - closed in config
- Instance name: the chart's `cloudSqlProxy.existingSecretName` points at
  `decision-engine-database-credentials` (key `db-cloud-sql-instance`), so no separate `cloudsql`
  Secret is needed. (fowf's own releases still reference a `<release>-cloudsql` Secret nothing
  creates - the same template approach would fix them.)
- Workload Identity: the release's ServiceAccount (`decision-engine`) is annotated with the GSA from
  the shared cluster file (`cloud.gcp.decisionEngine.serviceAccount`). gcp-openworkflow-prod:
  `openworkflow-prod-decision-eng@genai-llm-393115.iam.gserviceaccount.com`, declared as
  `workload_identities.decision_engine` (`roles/cloudsql.client`, member
  `decision-engine/decision-engine`) in openworkflow-k8s-setup's **gitignored** `terraform.tfvars`
  (also added to `terraform.example.tfvars`). New clusters: `decision-engine` in
  forwardmeasure-platform's `k8s-infrastructure/opentofu/gcp` `workload_service_accounts`.

### 4.4 Namespace - closed
forwardmeasure-platform's `manifests/namespaces.yaml` (applied first by its install.sh) now creates
`decision-engine`, `entity-intelligence` and `data-streaming`: platform-secrets writes into them, and
nothing created them before. This repo's own unused `deploy/helmfile/manifests/namespaces.yaml` was
removed.

### 4.5 `decisionEngine.enabled: true` - done
In `deploy/helmfile/environments/gcp-platform-cluster.yaml.gotmpl` (both GCP clusters).

### 4.6 Live steps still to do (in order)
1. `tofu apply` in openworkflow-k8s-setup/terraform/gcp - creates the decision-engine GSA and its
   Workload Identity binding. **Must happen before step 2**, or the proxy sidecar cannot get a token
   and the release fails its install (helmfile `atomic`).
2. `install-platform.sh gcp-openworkflow-prod` - platform first (namespace, Secrets), then fowf
   (`decision-engine-migrations` creates the `decision_engine` role, the `decision_intelligence`
   schema per tenant, and the `tenant_registry` grant), then decision-engine last.
3. Verify live: the pod is Ready (gRPC health probe), the proxy connects, and an
   Evaluation/Management call with a real `tenant-id` resolves the tenant's database and reads its
   rulesets.

### 4.7 Standalone deployment / `VerifiedJwtTenantResolver` - deliberately closed
`IMPLEMENTATION-SPEC.md` §13 item 8: enabling `VerifiedJwtTenantResolver` (or any further
per-caller authorization) needs a fresh, explicit decision at the point a standalone deployment
actually happens. Not affected by any of the above.

### 4.8 Worth knowing (not blocking)
- **The control-plane database was renamed (2026-09-29).** It used to be fowf's old shared
  `openworkflow` database (a stopgap from the database-per-tenant redesign), while Terraform also
  created an unused `platform` database meant for this role. Both are gone from Terraform; the one
  control-plane database is now `forwardmeasure_control_plane`, and forwardmeasure-jpa's
  `TenantDatabase` reserves the alias `control_plane` so no tenant can ever collide with it.
- `OpenWorkflowTenantMigrator.provisionAndMigrate` makes the calling product's runtime role the
  owner of the whole tenant database; fowf, fei and decision-engine all run it against the same
  `forwardmeasure_<alias>` database, so ownership ends with whichever migration ran last.
- The chart's `database.runtimeUsername` default is `decision_engine_runtime`; it only matters for
  the chart's in-pod migration path (embedded Postgres / `migration.enabled`), which no real
  environment uses.

## 5. File map

```
forwardmeasure-decision-engine/
  docs/IMPLEMENTATION-SPEC.md              - authoritative spec
  decision-engine-deployments/database-migration-service/
    RulesetMigrationsMain                  - tenant provisioning + tenant_registry grant
  deploy/helmfile/
    helmfile.yaml.gotmpl                   - decision-engine-valkey (embedded mode only), decision-engine
    environments/base.yaml.gotmpl          - local defaults (embedded Valkey)
    environments/gcp-platform-cluster.yaml.gotmpl - GCP profile, enabled: true, cloudSqlProxy on
    releases/decision-engine/base.yaml.gotmpl     - control-plane URL, Secrets, Workload Identity, proxy
forwardmeasure-openworkflow/
  deploy/helmfile/helmfiles/migrations.yaml.gotmpl  - decision-engine-migrations release (4.1)
  openworkflow-deployments/migrations/.../OpenWorkflowTenantMigrator - shared provisioning + grants
forwardmeasure-platform/
  deploy/helmfile/shared/                  - shared values (controlPlane database, endpoints, clusters/)
  deploy/helmfile/releases/platform-secrets/gcp/base.yaml.gotmpl - delivers every Secret in 4.1/4.2
  deploy/helmfile/manifests/namespaces.yaml - creates the decision-engine namespace
  k8s-infrastructure/opentofu/gcp/variables.tf - decision-engine GSA for new clusters
openworkflow-k8s-setup/terraform/gcp/terraform.tfvars (gitignored) - decision-engine GSA for prod
helm-charts/charts/decision-engine-helm-chart/ - the chart (0.1.6)
```

---

## 6. Hard constraints to not violate while doing any of the above (from `IMPLEMENTATION-SPEC.md` §13)

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
