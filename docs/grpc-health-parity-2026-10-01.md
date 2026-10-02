# gRPC health on every framework (2026-10-01)

**Status:** source changes only. Nothing is built, tested or committed. The user runs every Maven
build.

**Context.** On 2026-10-01, fowf, fei and FDS moved onto one error and security standard
(RFC 9457 `Problem`, identical 401/403/404 on every framework). FDE is gRPC-only, and
`IMPLEMENTATION-SPEC.md` forbids a REST surface. Its gRPC status mapping already lives once, in
framework-neutral `decision-engine-grpc`. The HTTP `Problem` standard doesn't apply here.

What did apply is the health probe: the chart's readiness and liveness probes are gRPC health
checks (`grpc: port: grpc`).

## Found (from the built images and jars, not run)

1. **Spring and Micronaut registered no `grpc.health.v1.Health`.**
   - Spring gRPC's `GrpcServerHealthAutoConfiguration` is
     `@ConditionalOnClass(io.grpc.protobuf.services.HealthStatusManager)`.
   - micronaut-grpc-health's `GrpcHealthFactory` is `@Requires(classes = HealthStatusManager)`.
   - `HealthStatusManager` lives in `io.grpc:grpc-services`, which was in neither image.
   - Quarkus serves health from quarkus-grpc itself (`quarkus.grpc.server.grpc-health.enabled`,
     default `true`).
   - So with `platform.framework: spring` or `micronaut`, FDE pods could never become Ready.
2. **Tenant resolution guarded health on every framework.** `TenantContextServerInterceptor` is
   registered globally on all three and resolves a tenant for every call. kubelet's gRPC probe
   sends no metadata, so it would get `UNAUTHENTICATED` on Quarkus too.

## Changed

- `io.grpc:grpc-services` on `decision-engine-spring` and `decision-engine-micronaut`. Spring pins
  it to `${grpc.version}`, next to `grpc-api` and `grpc-context`, because the Spring Boot BOM still
  manages gRPC at 1.80.
- `spring.grpc.server.reflection.enabled: false`. `grpc-services` would otherwise turn on Spring
  gRPC's reflection service (`matchIfMissing=true`). Quarkus serves reflection only in dev mode,
  and Micronaut never registers it.
- `TenantContextServerInterceptor` passes `grpc.health.v1.Health` calls straight through. Health
  carries no tenant data; this is the gRPC equivalent of an open HTTP health endpoint. The
  interceptor is shared, so the change applies identically on all three frameworks.

## Tests

- New `TenantContextServerInterceptorTest` (hand-written call and handler doubles, no Mockito):
  - health is reached without tenant metadata;
  - any other call without a tenant is closed `UNAUTHENTICATED`.
- `DecisionEngineContainerConformanceTest.verifyFramework` now first calls `Health/Check` with no
  metadata, exactly as kubelet does, and expects `SERVING` on each framework.
  - It runs only with `-Ddecision.engine.conformance.live=true`, after the three service images
    are built locally.
