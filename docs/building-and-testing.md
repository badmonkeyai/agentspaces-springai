# Building and testing

Written for: contributors who build and test this repository.

The project consumes the published AgentSpaces libraries by Maven coordinates
(`agentspaces.version` in the parent pom), resolved from Maven Central:

```
mvn clean verify
```

To build against unreleased core sources, install the core into the local
repository first and point `agentspaces.version` at it:

```
git clone https://github.com/badmonkeyai/AgentSpaces.git agentspaces
mvn -f agentspaces/pom.xml install -DskipTests
mvn clean verify
```

The example applications (`examples/`) build only with `-Pexamples`; CI runs
`mvn verify -Pexamples`, so they stay green with the library.

`mvn install -Pcentral-publish -Dgpg.skip=true` builds exactly what Maven
Central receives: the library's jar, sources jar, and javadoc jar, plus the
parent pom. See [CONTRIBUTING.md](../CONTRIBUTING.md).

Tests run on scripted `ChatModel` and `EmbeddingModel` beans, so no test calls a
live provider. Unit tests cover each component; `ApplicationContextRunner` tests
cover every auto-configuration condition and override; and flow tests run real
fleets over TCP: fleet tools, discovery, the capability tools voting and
contributing across two applications, a worker killed mid-loop that resumes on
another peer with its conversation, the model service with crashes, streaming,
cancellation, price routing, and trust, usage across peers, and MCP export to a
real MCP client.

| Module | What it is |
| --- | --- |
| [`core`](../core) | The library and its auto-configurations (artifact `agentspaces-springai`) |
| [`examples/springai-fleet`](../examples/springai-fleet/README.md) | Example 15: a Spring AI orchestrator on the fleet |
| [`examples/springai-patterns`](../examples/springai-patterns/README.md) | Judges, token-priced auctions, extraction ensembles |
