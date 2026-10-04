# agentspaces-springai

AgentSpaces for Spring AI applications. Spring AI gives an application a
portable, well-engineered way to call models; AgentSpaces gives a fleet of
processes a shared, leased, signed, replicated tuple space. This project plugs
the fleet into Spring AI's own extension points (tools, advisors, chat memory,
`ChatModel`, `EmbeddingModel`, observations, and MCP), so an application stays
ordinary Spring AI code while its agents coordinate across machines.

It needs no Embabel. The Embabel extension (`embabel-agentspaces` in the core
repository) integrates at the level of a planned agent and its actions; this
project integrates at the level of a model call and its tools. An application
can use either or both.

Requirements: Java 21, Spring Boot 4.1, Spring AI 2.0, and the AgentSpaces
starter (`agentspaces-spring-boot-starter`). The design and its rationale are in
[`docs/design.md`](docs/design.md).

## Getting started

Add the library beside the starter and a Spring AI model starter:

```xml
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-springai</artifactId>
  <version>0.2.0</version>
</dependency>
```

A worker is a `@SpaceAgent` bean with an injected `ChatClient.Builder` and a
`@SpaceTake` method. The space distributes the work, the model does it, and the
take lease renews itself on every model round-trip:

```java
@SpaceAgent(description = "Summarizes a topic into a short briefing")
public class Summarizer {
    private final ChatClient chat;

    Summarizer(ChatClient.Builder builder) { this.chat = builder.build(); }

    @SpaceTake(space = "work", lease = "2m")
    public Summary summarize(Brief brief) {
        return new Summary(brief.topic(), chat.prompt().user("Summarize: " + brief.topic()).call().content());
    }
}
```

An orchestrator attaches the fleet's tools to its `ChatClient`, and its model
calls agents on other peers from its own tool loop:

```java
@Bean
Orchestrator orchestrator(ChatClient.Builder builder, FleetTools fleet) {
    return new Orchestrator(builder.defaultToolCallbacks(fleet.toolCallbacks()).build());
}
```

[Example 15](examples/springai-fleet/README.md) runs exactly this across three
Spring Boot applications.

## Features

| Feature | Beans | What it gives an application |
| --- | --- | --- |
| F1. Fleet tools | `FleetTools` | The fleet's AgentCards as Spring AI `ToolCallback`s. Each request sees the live tool set; a `FleetToolsChangedEvent` announces changes. |
| F2. Discovery and data tools | `FleetDiscoveryTools` | `find_fleet_agents`, `list_fleet_assets`, and `fetch_fleet_data`, each enabled separately. |
| F3. Crash-safe LLM workers | `TakeLeaseAdvisor`, `SpaceChatMemoryRepository`, `FleetChatMemoryAdvisor` | A take lease that renews on every model round-trip, and chat memory in a replicated space, so a task that reappears after a crash resumes with its conversation. |
| F4. Model access as a fleet service | `FleetChatModel`, `ModelServer` | A `ChatModel` whose provider is the fleet: crash tolerance, routing by model or by price, media over the block exchange, streaming with cancellation and restart policies. |
| F5. Real embeddings | `SpringAiEmbedder` | Semantic discovery ranks with the application's `EmbeddingModel`, and the embedder's identity is advertised so peers query only peers that rank alike. |
| F6. Fleet-wide usage | `FleetUsageObservationHandler`, `FleetUsage`, `UsagePanel` | Token usage from Spring AI's `gen_ai` observations, as fleet-wide push-sum totals, attributed usage entries, and a console panel. |
| F7. MCP export | `FleetMcpToolSync` | The fleet agents a deployment names, published through Spring AI's MCP server and kept in step with `list_changed`. |
| F8. Patterns | `VectorStoreAssetProvider` (served with `vector-store.enabled=true`); [examples/springai-patterns](examples/springai-patterns/README.md) | A `VectorStore` as a fleet data asset; model judges voting with `@Ballot`; models priced by tokens under AUCTION; an extraction ensemble. |

Each feature has its own auto-configuration and its own `enabled` property;
F1 and the take-lease advisor are on by default, and every feature that widens
what a model can reach or sends prompts across the fleet is off by default.

### A note on routing

A fleet tool writes its input as a task, and in a tuple space the task goes to
whichever agent takes its type. A tool's card names one agent that does, and
the tool's description says so; it does not pin the work to that agent. To keep
an agent's work private, give it a request type of its own, or restrict who may
take from the space with `admission: authorizer` and the `SPACE_TAKE` grant.

### Why FleetTools is not a ToolCallbackProvider bean

Spring AI's MCP server auto-configuration publishes every `ToolCallbackProvider`
bean in the context to outside MCP clients. `FleetTools` and
`FleetDiscoveryTools` are therefore plain beans that hand out their providers
through `toolCallbacks()`, which the application attaches to a `ChatClient`
explicitly. The fleet crosses into MCP only through F7, which exposes nothing
until `agentspaces.springai.mcp.include-agents` names the agents it may.

## Extension points

Every decision a deployment might want to change is an interface with one
default bean. Declaring a bean of the interface replaces the default.

| Interface | Default | Replace it to |
| --- | --- | --- |
| `FleetToolFilter` | include and exclude lists and `require-attested` | apply a custom trust rule to which cards become tools |
| `ToolNamingStrategy` | `fleet_` prefix, sanitized, truncated, deduplicated | follow a tool naming convention |
| `ConversationIdResolver` | `memory.conversation-id`: `take`, `agent`, or `explicit` | key conversations by tenant, user, or session |
| `ModelCatalog` | the names in `model-server.models`, served by the provider `ChatModel` | serve models from a registry |
| `ModelRequestRouter` | by model name, or by price under AUCTION | route by tenant, region, or load |
| `StreamChunkingPolicy` | 100 ms or 32 deltas per chunk | trade latency against entry count |
| `StreamRestartPolicy` | `fail` or `restart` | recover a stream whose server died in a custom way |
| `UsageSink` | fleet totals, usage entries, console panel | export usage to a billing or FinOps system |

Spring AI's own interfaces are the rest of the API: the components are a
`ToolCallbackProvider` (through `toolCallbacks()`), a `ChatMemoryRepository`, a
`ChatModel`, a `CallAdvisor` and `StreamAdvisor`, and an `ObservationHandler`.

## Configuration

All properties sit under `agentspaces.springai`. The spaces that F2, F3, F4,
F6, and F8 use must be declared under `agentspaces.groups[].spaces`; a missing one
fails at startup with the YAML that adds it.

```yaml
agentspaces:
  groups:
    - name: research-fleet
      founding: research-fleet-v1
      spaces:
        - { name: work }
        - { name: conversations }     # F3 memory
        - { name: model-requests }    # F4 model service
  springai:
    tools:
      prefix: fleet_
      exclude-agents: [internal-auditor]
    memory:
      enabled: true
      conversation-id: take
    model-client:
      enabled: true                   # this node calls models through the fleet
    model-server:
      enabled: false                  # true on the peers that hold provider keys
      models: [gpt-4.1-mini]
    embedder:
      type: spring-ai
      model: text-embedding-3-small
    usage:
      enabled: true
    mcp:
      enabled: false
      include-agents: []
```

| Property | Default | Meaning |
| --- | --- | --- |
| `group` | first group | The group this project works in |
| `tools.enabled` | `true` | Register `FleetTools` |
| `tools.prefix`, `tools.include-agents`, `tools.exclude-agents`, `tools.require-attested` | `fleet_`, all, none, `false` | Tool naming and filtering |
| `tools.timeout`, `tools.on-timeout` | `30s`, `result` | How long a tool call waits, and whether a timeout returns an explanation or throws |
| `tools.refresh-interval`, `tools.cache-ttl` | `5s`, `1s` | Change-event cadence and snapshot caching |
| `discovery-tools.agents`, `.assets`, `.data` | `false` | Enable each discovery tool |
| `discovery-tools.data-space`, `.timeout`, `.max-result-chars` | `data`, `10s`, `20000` | Data space, query timeout, result cap |
| `take-lease.enabled`, `take-lease.stream-renew-interval` | `true`, `30s` | The lease advisor |
| `memory.enabled`, `.space`, `.ttl`, `.conversation-id`, `.max-messages` | `false`, `conversations`, `24h`, `take`, `20` | Chat memory in the space |
| `model-client.enabled`, `.space`, `.model`, `.timeout`, `.stream.on-restart` | `false`, `model-requests`, server default, `120s`, `fail` | The calling side of F4 |
| `model-server.enabled`, `.space`, `.models`, `.routing`, `.prices.<model>` | `false`, `model-requests`, none, `model`, `1.0` | The serving side of F4 |
| `model-server.lease`, `.chunk-interval`, `.chunk-max-deltas`, `.chunk-lease`, `.response-lease`, `.concurrency`, `.model-beans.<model>` | `2m`, `100ms`, `32`, `2m`, `10m`, `4`, none | Server tuning; `model-beans` maps a model to a `ChatModel` bean when several providers are present |
| `embedder.type`, `.model`, `.require-match`, `.cache-size` | `hashing`, class name, `true`, `4096` | F5 |
| `usage.enabled`, `.metrics-space`, `.entry-lease`, `.publish-interval` | `false`, none, `24h`, `30s` | F6 |
| `mcp.enabled`, `.include-agents`, `.discovery-tools` | `false`, none, `false` | F7 |
| `vector-store.enabled`, `.space`, `.asset`, `.description`, `.freshness` | `false`, `data`, `knowledge-base`, a generic description, `5m` | F8: serve the application's `VectorStore` bean as a fleet data asset |

## Security

- **Prompts cross the fleet under F3 and F4.** Give the conversation and model
  spaces their own admission (`allowlist`, `credential`, or `authorizer`) and a
  group content key.
- **Only trusted servers answer.** `FleetChatModel` accepts a response or stream
  chunk only from an issuer the authorizer permits `MODEL_SERVE` for the model:
  `agentspaces.security.grants.model-serve` under the membership profiles, the
  `aspace:model-serve[:<model>]` scope under the OIDC profiles.
- **Tool descriptions are model input.** Filter which cards become tools, and
  consider `tools.require-attested=true` with subordinate agent keys.
- **Credentials stay on server peers.** Under F4, provider keys live only on the
  `ModelServer` peers.
- **MCP clients are outside the fleet.** F7 exposes nothing until named.

## Building and testing

The project consumes the published AgentSpaces libraries by Maven coordinates.
Once the core artifacts at `agentspaces.version` are on Maven Central:

```
mvn clean verify
```

Until then, install the core into your local repository first:

```
git clone https://github.com/badmonkeyai/agentspaces.git
mvn -f agentspaces/pom.xml install -DskipTests
mvn clean verify
```

The example applications (`examples/`) build only with `-Pexamples`; CI runs
`mvn verify -Pexamples`, so they stay green with the library.

`mvn install -Pcentral-publish -Dgpg.skip=true` builds exactly what Maven
Central receives: the library's jar, sources jar, and javadoc jar, plus the
parent pom. See [CONTRIBUTING.md](CONTRIBUTING.md).

Tests run on scripted `ChatModel` and `EmbeddingModel` beans, so no test calls a
live provider. Unit tests cover each component; `ApplicationContextRunner` tests
cover every auto-configuration condition and override; and flow tests run real
fleets over TCP: fleet tools, discovery, a worker killed mid-loop that resumes on
another peer with its conversation, the model service with crashes, streaming,
cancellation, price routing, and trust, usage across peers, and MCP export to a
real MCP client.

| Module | What it is |
| --- | --- |
| [`core`](core) | The library and its auto-configurations (artifact `agentspaces-springai`) |
| [`examples/springai-fleet`](examples/springai-fleet/README.md) | Example 15: a Spring AI orchestrator on the fleet |
| [`examples/springai-patterns`](examples/springai-patterns/README.md) | Judges, token-priced auctions, extraction ensembles |

## Contributing

Contributions are welcome under the Developer Certificate of Origin; see
[CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).
Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes.

## License

Copyright 2026 Bad Monkey, Inc.

AgentSpaces Spring AI is an open source project from Bad Monkey, Inc, licensed
under the Apache License 2.0; see `LICENSE`.

For questions, contact `oss@badmonkey.ai`
