# Decision Engine handover

Current implementation and operator instructions:
[2026-10-06 FDE rehabilitation and FOWF integration](docs/rehabilitation/fde-rehabilitation-handover-2026-10-06.md).

Production repairs and the real FOWF gRPC integration are implemented. Production/test sources
compile; Quarkus, Spring, Micronaut and migration assemblies package. Behavioral tests have not
been executed, images have not been rebuilt/pushed, and FDE has not been activated in the cluster.
Changes are local/uncommitted. The detailed record lists the five affected images, infrastructure
and chart updates, exact commands, remaining verification and supported tenant-onboarding boundary.

Claude owns independent tests/fixtures and review; Codex owns production fixes. Start from the
[shared handover](../forwardmeasure-openworkflow/CLAUDE_HANDOVER.md). September's internal-trust
and deployment claims are historical and superseded.
