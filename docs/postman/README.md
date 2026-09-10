# Decision Engine Postman workspace

The decision engine exposes native gRPC services. In Postman, create a gRPC request and import the
protobuf files from `decision-engine-api-specifications/src/main/resources/META-INF/proto`, or use
server reflection if it is enabled in the running deployment.

The request target is the selected deployment's gRPC endpoint, for example the mapped port printed by
the live Testcontainers suite. The `ruleset` field explicitly selects
the persisted ruleset. Omitting `ruleset_version` evaluates the active version; setting it pins the
call to an exact version. Stateful calls additionally set `session_key`.

The golden rulesets and expected cases used by the automated suite live under
`decision-engine-conformance-tests/src/test/resources/golden`.

The collection also includes the local `DecisionEngineAdminService` calls for statistics, cache
inspection, warming, unloading, and clearing compiled rules. These operations affect only the
serving process; they do not delete persisted rulesets or fact windows and must be protected by the
deployment boundary.

Run the normal checks with:

```bash
mvn verify -Dcontainer-image.build=false -Dcontainer-image.push=false
```

After building the four local images with the `container-image` Maven profile, run the complete
black-box suite with:

```bash
mvn -pl decision-engine-conformance-tests -am test \
  -Ddecision.engine.conformance.live=true \
  -Dcontainer-image.build=false -Dcontainer-image.push=false
```
