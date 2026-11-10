# Features

Written for: Spring AI developers who build with agentspaces-springai.

| Feature | Beans | What it gives an application |
| --- | --- | --- |
| F1. Fleet tools | `FleetTools` | The fleet's AgentCards as Spring AI `ToolCallback`s. Each request sees the live tool set; a `FleetToolsChangedEvent` announces changes. |
| F2. Discovery and data tools | `FleetDiscoveryTools` | `find_fleet_agents`, `list_fleet_assets`, and `fetch_fleet_data`, each enabled separately. |
| F3. Crash-safe LLM workers | `TakeLeaseAdvisor`, `SpaceChatMemoryRepository`, `FleetChatMemoryAdvisor` | A take lease that renews on every model round-trip, and chat memory in a replicated space, so a task that reappears after a crash resumes with its conversation. |
| F4. Model access as a fleet service | `FleetChatModel`, `ModelServer` | A `ChatModel` whose provider is the fleet: crash tolerance, routing by model or by price, media over the block exchange, streaming with cancellation and restart policies. |
| F5. Real embeddings | `SpringAiEmbedder` | Semantic discovery ranks with the application's `EmbeddingModel`, and the embedder's identity is advertised so peers query only peers that rank alike. |
| F6. Fleet-wide usage | `FleetUsageObservationHandler`, `FleetUsage`, `UsagePanel` | Token usage from Spring AI's `gen_ai` observations, as per-peer totals summed into a fleet total, attributed usage entries, and a console panel. Each peer republishes its running total per model on `usage.publish-interval` under a lease of three intervals (ten seconds at least); a peer that stops publishing leaves the sum when its last total lapses, and a restarted peer's count begins at zero. The fleet total is an operational figure; the per-call `ModelUsage` entries are for billing. |
| F7. MCP export | `FleetMcpToolSync` | The fleet agents a deployment names, published through Spring AI's MCP server and kept in step with `list_changed`. |
| F8. Patterns | `VectorStoreAssetProvider` (served with `vector-store.enabled=true`); [examples/springai-patterns](../examples/springai-patterns/README.md) | A `VectorStore` as a fleet data asset; model judges voting with `@Ballot`; models priced by tokens under AUCTION; an extraction ensemble. |
| F9. Capability tools | `FleetCapabilityTools` | `propose_vote`, `cast_ballot`, `read_tally`, `read_decision`, `contribute`, and `read_estimate`: the model opens votes, casts ballots, and contributes to fleet-wide averages as this peer's agent, each family enabled separately. With `capability-tools.content` the model also publishes a file as a fleet artifact and fetches, reads, describes, and lists artifacts moved by the content swarm (SPEC v0.1.23 §8); a `ModelRequest` whose media exceeds `agentspaces.content.escalate-above` travels as an artifact on its own. With `capability-tools.pdt` (and `agentspaces.pdt.enabled`) the model reads a path of a persistent map ref, puts a value at a path as a new version every member merges, and lists the versions: `pdt_read`, `pdt_assoc`, `pdt_history` (srad/agentspaces-pdt.md PD-C-09). The same family pins a root on the group's content stores and reads which stores hold it: `pdt_pin` (copies and hours; this peer signs the pin) and `pdt_store_status` (one receipt per store, the copies that count); a peer becomes a store with `agentspaces.store.enabled` (srad/agentspaces-pdt2.md P2-C-04). |

Each feature has its own auto-configuration and its own `enabled` property;
F1 and the take-lease advisor are on by default, and every feature that widens
what a model can reach or sends prompts across the fleet is off by default.

## A note on routing

A fleet tool writes its input as a task, and in a tuple space the task goes to
whichever agent takes its type. A tool's card names one agent that does, and
the tool's description says so; it does not pin the work to that agent. To keep
an agent's work private, give it a request type of its own, or restrict who may
take from the space with `admission: authorizer` and the `SPACE_TAKE` grant.

## Why FleetTools is not a ToolCallbackProvider bean

Spring AI's MCP server auto-configuration publishes every `ToolCallbackProvider`
bean in the context to outside MCP clients. `FleetTools`, `FleetDiscoveryTools`,
and `FleetCapabilityTools` are therefore plain beans that hand out their
providers through `toolCallbacks()`, which the application attaches to a
`ChatClient` explicitly. The fleet crosses into MCP only through F7, which
exposes nothing until `agentspaces.springai.mcp.include-agents` names the agents
it may, and `mcp.discovery-tools` and `mcp.capability-tools` say the F2 and F9
tools go too.

## The 0.3.0 annotations

The starter binds every `@SpaceAgent` bean through the core's `AgentBinder`,
and this project adds nothing between them, so the 0.3.0 annotation surface
works in a Spring AI application unchanged: `@Propose` opens a vote for each
cue, `@OnEstimate` fires when a push-sum epoch settles, `@SpaceJoin` gathers
the parts of one result, `@SpaceReduce` folds a stream of entries, `tags` and
`where` filter what a method sees before it is decoded, and a method may return
`Tagged`, `Entries`, or `Motion` in place of a plain entry. A reviewer that puts
each finding to the fleet, with a model writing the question:

```java
@SpaceAgent(description = "Asks the fleet whether a finding needs remediation")
public class Reviewer {
    private final ChatClient chat;

    Reviewer(ChatClient.Builder builder) { this.chat = builder.build(); }

    @Propose(space = "findings", vote = "votes", prefix = "remediate:", key = "findingId",
            options = {"approve", "reject"}, quorum = 3, lease = "12h", where = "severity=high")
    public String ask(Finding finding) {
        return chat.prompt().user("In one sentence, what should the fleet decide about: " + finding).call().content();
    }
}
```

The `votes` space is the starter's vote space (`agentspaces.capabilities.votes-space`,
`votes` by default); `@Ballot` and `@OnDecision` methods elsewhere in the fleet
filter on the `remediate:` prefix as before. The
[patterns example](../examples/springai-patterns/README.md) opens its judges' votes
with a `Motion` return. `AnnotationPassthroughTest` in `core/src/test` boots two
Spring Boot applications and proves each of these annotations and return
conventions through `@SpaceAgent` beans, reading every outcome from a space.
