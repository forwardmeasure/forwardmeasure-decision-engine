# forwardmeasure-decision-engine

General-purpose, gRPC-exposed, Drools-based rule evaluation server. Evaluated in parallel
against the OPA-based `PolicyEvaluator` in `forwardmeasure-agent-os`.

**Start here: [docs/IMPLEMENTATION-SPEC.md](docs/IMPLEMENTATION-SPEC.md).** It is the
authoritative, prescriptive spec for this repository — module boundaries, the two `.proto`
contracts, the Drools embedding approach, the stateful-ruleset fact-window design, deployment,
testing requirements, and explicit non-goals. Everything in this repository outside
`decision-engine-domain` is currently a Maven-module skeleton (`pom.xml` + package structure,
no implementation) for an autonomous coding agent to fill in against that spec — do not assume
an empty module is unimplemented by accident.

Module layout, tech stack, and architecture diagram: spec Section 2–4.
