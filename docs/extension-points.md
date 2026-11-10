# Extension points

Written for: Spring AI developers who build with agentspaces-springai.

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
