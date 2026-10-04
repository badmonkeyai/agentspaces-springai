# agentspaces-springai: AgentSpaces for Spring AI applications

> This design memo was written in the AgentSpaces development workspace, where this project sat beside the core repository (`agentspaces/`), the Clojure bindings, and Party Bus. Paths such as "the workspace root" refer to that layout; in this repository the project root is the workspace root's `agentspaces-springai/`.

Status: implemented, 2026-09-30; every task of the implementation plan verified done, 2026-10-03. Sections 1 to 11 are the approved design; section 13 records how the build departed from it and what remains.
Scope: a new standalone project, `agentspaces-springai`, that lets a Spring AI
application join an AgentSpaces fleet and use it directly, with no Embabel
dependency; and the platform upgrade to Spring AI 2.0, Spring Boot 4, Spring
Framework 7, and Embabel 1.5 that it requires.

## 1. Summary

Spring AI gives a JVM application a portable, well-engineered way to call
models: `ChatClient`, structured output, tools, MCP, chat memory, RAG, and
Micrometer observations. Everything it does happens inside one process and one
call. AgentSpaces gives a fleet of processes a shared, leased, signed,
replicated tuple space, with discovery, votes, auctions, and exactly-once takes.
The two are orthogonal, and each maps onto a gap in the other.

A Spring AI developer can already use AgentSpaces by injecting a `ChatClient`
into a `@SpaceAgent` bean and calling it inside a `@SpaceTake` method. That
stays the entry point. `agentspaces-springai` adds fleet-backed
implementations of Spring AI's own extension points:

| Feature | What it gives a Spring AI application |
| --- | --- |
| F1. Fleet tools | The fleet's AgentCards become Spring AI `ToolCallback`s, so the model's tool loop can call agents anywhere on the P2P fleet |
| F2. Discovery and data tools | `FleetDiscoveryTools`: the model can find fleet agents by meaning and read enterprise data through the connector SDK |
| F3. Crash-safe LLM workers | A take lease that renews on every model round-trip, and `SpaceChatMemoryRepository`, so a reassigned task resumes with its conversation |
| F4. Model access as a fleet service | `FleetChatModel` implements `ChatModel` over the space, with crash tolerance, routing, media, and streaming; `ModelServer` serves it from the peers that hold the credentials |
| F5. Real embeddings for semantic discovery | `SpringAiEmbedder` over `EmbeddingModel`, with the embedder advertised on the capability advertisement |
| F6. Fleet-wide usage | Spring AI's `gen_ai` token usage as fleet aggregates, attributed entries, and a console panel |
| F7. MCP export | The fleet's tools published through Spring AI's MCP server to any MCP client |
| F8. Patterns | Documented combinations of existing annotations with Spring AI, plus a `VectorStore` asset provider |

## 2. Decisions

The review of the first draft settled these questions.

| Question | Decision |
| --- | --- |
| Where the code lives | A standalone project at the workspace root, `agentspaces-springai/`, beside `agentspaces-clj/` and `agentspaces-partybus/`. It consumes the AgentSpaces artifacts by Maven coordinates. |
| Relationship to Embabel | Separate from `embabel-agentspaces`, with no dependency in either direction. The two work at different granularities: Embabel integrates at the level of a planned agent and its actions, and Spring AI integrates at the level of a model call and its tools. |
| Features in scope | F1 through F8, all of them. |
| Open source and enterprise boundary | Everything in this document is open source. `aspace:cap/llm-gateway` (deduplication against a response cache, per-group budgets, cross-provider routing) and `aspace:cap/result-cache` stay enterprise offerings, so F4 builds no deduplication and no budgets. |
| Spring AI versions | Spring AI 2.0.x only. |
| Platform upgrade | Spring AI 2.0, Spring Boot 4, Spring Framework 7, and Embabel 1.5 across every project that uses Spring or Embabel, with the dependencies updated, every test suite re-run, and compatibility fixes made where the newer versions require them. |
| Streaming | In scope for F4, with an architecture and tests that support it from the first release. |
| A2A | Unchanged. The fleet keeps its A2A gateway, and this project adds no A2A features. |

## 3. What Spring AI provides

Current release: Spring AI **2.0.1** (2026-08-21). 2.0.0 went GA on
2026-06-12 on Spring Boot 4.0/4.1, Spring Framework 7.0, Jackson 3, and MCP Java
SDK 2.0. 2.1.0-M1 (2026-09-25) builds on Boot 4.2.

| Area | What it offers (2.0 names) |
| --- | --- |
| Model API | `ChatModel.call(Prompt)`, `StreamingChatModel.stream(Prompt)` returning `Flux<ChatResponse>`, `Prompt` of `Message`s (`UserMessage`, `SystemMessage`, `AssistantMessage` with tool calls, `ToolResponseMessage`), immutable `ChatOptions` with `mutate()`, `ChatResponse` with `ChatResponseMetadata.getUsage()`. In 2.0, `ChatModel.call` no longer runs tools. |
| Fluent client | `ChatClient.prompt().system(..).user(..).advisors(..).tools(..).call().entity(Foo.class)`, and `.stream().content()`, with builder defaults (`defaultSystem`, `defaultAdvisors`, `defaultTools`, `defaultOptions`). |
| Structured output | `entity(Class)`, `entity(ParameterizedTypeReference)`, `BeanOutputConverter`, provider-native structured output, and `StructuredOutputValidationAdvisor` to repair invalid JSON. |
| Advisors | `CallAdvisor.adviseCall(ChatClientRequest, CallAdvisorChain)` and `StreamAdvisor.adviseStream(..)`, ordered stack-style. An advisor can short-circuit by returning its own response without calling the chain. Built-ins include chat memory, `QuestionAnswerAdvisor`, `RetrievalAugmentationAdvisor`, `SafeGuardAdvisor`, `SimpleLoggerAdvisor`. |
| Tool loop | `ToolCallingAdvisor` at order `HIGHEST_PRECEDENCE + 300` runs the tool loop inside `ChatClient`; advisors ordered after it run inside the loop, once per model round-trip. `ToolSearchToolCallingAdvisor` discloses large tool sets progressively. Limits via `ToolCallingManager` (`maxCallsPerTool`, `maxTotalToolCalls`). |
| Tools | `@Tool` and `@ToolParam`, `ToolCallback` (`getToolDefinition()`, `call(String)`, `call(String, ToolContext)`), `ToolCallbackProvider`, `MethodToolCallbackProvider`, `FunctionToolCallback`. |
| MCP | Client and server Boot starters (STDIO, Streamable HTTP by default, SSE deprecated). MCP tools arrive as `ToolCallbackProvider`s; `@McpTool`, `@McpResource`, `@McpPrompt` on the server side. |
| Chat memory | `ChatMemory` keyed by a required `ChatMemory.CONVERSATION_ID`, `MessageWindowChatMemory`, and `ChatMemoryRepository` implementations (in-memory, JDBC, Cassandra, Neo4j, MongoDB, Redis). Tool-call intermediate messages are not stored. |
| RAG | `VectorStore` (about twenty stores), `SearchRequest` with filter expressions, the ETL pipeline, and modular RAG components. |
| Embeddings | `EmbeddingModel` over every major provider. |
| Observability | Micrometer observations `spring.ai.chat.client`, `gen_ai.client.operation`, `spring.ai.advisor`, `spring.ai.tool`, following the OpenTelemetry GenAI semantic conventions; the `gen_ai.client.token.usage` metric. |
| Evaluation | `Evaluator` with `RelevancyEvaluator` and `FactCheckingEvaluator` (LLM-as-judge). |
| Agent patterns | The "Building Effective Agents" page documents chain, routing, parallelization, orchestrator-workers, and evaluator-optimizer as example code over `ChatClient`; core Spring AI has no agents module. An experimental "Spring AI Agents" project is announced for November 2026. |

**What Spring AI leaves to the application.** It has no coordination across
JVMs, no durable work queue, no task leasing or crash recovery for an in-flight
tool loop, no registry or discovery of agents, no consensus, voting, or
auctions, and no signed or attributable messages. Its retries are HTTP-level.
Chat memory persists a message log, and it is not a process checkpoint.

## 4. What Embabel provides, and how AgentSpaces uses it today

Current release: Embabel **1.5.2** (2026-09-16), Apache 2.0, Kotlin with a Java
API, Java 21. Its 1.5.2 POM pins Spring AI 2.0.1, Spring Framework 7.0.9, and
Spring Boot 4.1.1. Embabel builds on Spring AI: `SpringAiLlmService` wraps a
Spring AI `ChatModel`, and its prompt runner executes through `ChatClient`.

Embabel adds planning on top of Spring AI: `@Agent`, `@Action` (with `pre`,
`post`, `cost`, `value`, `canRerun`), `@AchievesGoal`, `@Condition`, a typed
`Blackboard`, GOAP, Utility, Hybrid, and Supervisor planners that replan after
every action, `OperationContext.ai().withLlm(..).createObject(..)`,
`AgentPlatform`, `AgentProcess`, `Autonomy`, and single-node process
checkpointing. It has no cross-JVM distribution: an agent process and its
blackboard live in one `AgentPlatform`.

**The Embabel extension today** (`agentspaces/embabel-agentspaces`) reads
Embabel reflectively, so it builds with no Embabel artifact:

- `EmbabelBinder` publishes each `@Agent` as an AgentCard through
  `AgentBinder.adopt(card)`.
- `EmbabelRemoteActions` generates a typed `@Agent` with Byte Buddy, one
  `@Action` per foreign card and type pair, each delegating to
  `RemoteAction.invoke(input, timeout)`.
- `EmbabelRemoteActionsDeployer` redeploys that agent onto the `AgentPlatform`
  when the set of actions changes.

The Embabel extension hands the fleet to Embabel's GOAP planner. A Spring AI
application has no planner; its decision-maker is the model itself, choosing
tools inside `ToolCallingAdvisor`'s loop. That difference in granularity is why
the two integrations are separate projects.

Three facts from the codebase frame the upgrade:

- No code in the workspace uses Spring AI today.
- The starter compiles against `agentspaces-spring-stubs` and has been proven
  against real Spring Boot only at 3.3.5, in `integration-tests/spring-boot-it`.
- The Party Bus pins Embabel 0.3.2, calls OpenAI over raw HTTP in
  `LiveSources.structured`, and compiles its Embabel sources only under the
  `embabel` profile, which `verify-all.sh` does not activate. No build in the
  workspace compiles against a real Embabel artifact today.

## 5. Strategy

### 5.1 Three levels of integration

**Level 0, no library.** A `@SpaceAgent` bean injects `ChatClient.Builder` and
calls the model inside `@SpaceTake`, `@SpaceNotify`, `@Ballot`, or
`@BidFunction`. The space distributes the work and the model does it. The
project adds no new worker annotation.

```java
@SpaceAgent(description = "Researches topics from the shared task space")
public class Researcher {
    private final ChatClient chat;

    Researcher(ChatClient.Builder builder) { this.chat = builder.build(); }

    @SpaceTake(lease = "2m")   // short: TakeLeaseAdvisor renews it while the model works
    public Finding research(ResearchTask task) {
        return chat.prompt().user("Research, with citations: " + task.topic())
                .call().entity(Finding.class);
    }
}
```

**Level 1, fleet-aware Spring AI.** The project plugs fleet-backed
implementations into Spring AI's extension points: tools (F1, F2), advisors and
chat memory (F3), embeddings (F5), and observations (F6). The application code
stays Spring AI code.

**Level 2, the fleet as model infrastructure.** `FleetChatModel` (F4) routes
model calls through the space to peers that serve them, so agents need no
provider credentials, and model access becomes a leased, observable,
crash-tolerant fleet service.

### 5.2 Layering

```
  application code          ChatClient, @Tool, @SpaceAgent, @SpaceTake
  ───────────────────────────────────────────────────────────────────
  agentspaces-springai      model-call granularity: fleet tools, lease
  (standalone project)      advisor, space memory, FleetChatModel and
                            ModelServer, embedder, usage, MCP export
  embabel-agentspaces       agent granularity: GOAP planner over fleet
  (core reactor)            actions, cards from @Agent metadata
  Spring AI 2.0             ChatModel, ChatClient, advisors, tools, MCP
  agentspaces-agent         AgentBinder, AgentSpaces, RemoteActions
  agentspaces-space ...     the fabric, Layers 0 to 4
```

The two integrations are independent, and an application can use either or
both. An Embabel application that also adds `agentspaces-springai` can wrap
`FleetChatModel` in Embabel's `SpringAiLlmService`, and pass fleet tools to the
prompt runner as tool objects.

### 5.3 How Spring AI's agent patterns become distributed

| Spring AI pattern | In one JVM | On the fleet |
| --- | --- | --- |
| Chain | sequential `ChatClient` calls | service choreography: each stage is a `@SpaceNotify` or `@SpaceTake` that returns the next stage's entry |
| Parallelization | an executor over prompts | N replicas of a `@SpaceTake` worker drain one space, and a crash lapses the lease |
| Routing | a classifier prompt picks a handler | AgentCards route by entry type, or AUCTION routes by a `@BidFunction` cost model |
| Orchestrator-workers | an orchestrator prompt fans out subtasks | the orchestrator writes tasks and reads results; with fleet tools (F1), the orchestrator's model calls remote workers directly |
| Evaluator-optimizer | a judge prompt loops on a draft | a panel of `@Ballot` judges votes, each with its own model or `Evaluator`, and `@OnDecision` accepts or returns the draft |

## 6. Feature design

Code in this section sketches the intended shape against Spring AI 2.0.1 names.
Items marked "verify" needed checking against the 2.0.1 sources; section 13
records what the checks found.

### F1. Fleet tools

`FleetToolCallbackProvider implements ToolCallbackProvider` turns the fleet's
foreign AgentCards into tools, so a Spring AI agent can use the whole P2P fleet
from its tool loop. It wraps the existing `RemoteActions`, which already
computes invocable actions from cards, resolves their task and result spaces by
the established precedence (explicit route, the card's space binding, the sole
space), and correlates results.

```java
@Bean
ChatClient orchestrator(ChatClient.Builder builder, FleetTools fleet) {
    return builder.defaultToolCallbacks(fleet.toolCallbacks()).build();
}
// the model now sees tools such as fleet_summarizer_SummaryTask and
// fleet_translator_TranslateTask, and calls them like any other tool
```

Each `RemoteAction` becomes one `RemoteActionToolCallback`:

| Tool element | Source |
| --- | --- |
| name | `RemoteAction.name()` (`<agent>_<InputType>`), prefixed (`fleet_` by default), sanitized to `[A-Za-z0-9_-]`, truncated to 64 characters, deduplicated |
| description | the card's description and goals, which `RemoteAction` already strips of control characters and caps (ASF-030), plus the produced type's simple name |
| input schema | a JSON Schema generated from the input record type with Spring AI's schema generator (verify: `JsonSchemaGenerator.generateForType`) |
| call | deserialize the model's JSON into the input record, `action.invoke(input, timeout)`, serialize the result record to JSON |

- **Live tool set.** `getToolCallbacks()` returns the current snapshot of
  `RemoteActions.available()`, cached for a short interval. A card that lapses
  disappears from the next request's tool set. A scheduled task on the
  auto-configured `TaskScheduler` diffs the tool set, and publishes a
  `FleetToolsChangedEvent` with the added and removed tools whenever it
  changes, so other components react without polling. If `ChatClient` resolves
  providers only at build time (verify), the provider returns stable
  dispatching callbacks and refreshes their targets.
- **Timeouts.** An empty result from `invoke` returns a tool result that tells
  the model the fleet produced no result within the timeout, so the model can
  adapt; `tools.on-timeout=throw` raises instead.
- **Input validation.** Deserialization into the declared record type rejects
  unknown and missing fields before anything is written to a space.
- **Filtering.** Include and exclude lists by agent name, issuer, and group
  decide which cards become tools, and `tools.require-attested=true` limits
  tools to `AGENT_ATTESTED` cards (section 9).
- **Large fleets.** With many cards, pair the provider with
  `ToolSearchToolCallingAdvisor`, and let F2's discovery tool act as the search
  step.

### F2. Discovery and data tools

`FleetDiscoveryTools` offers `@Tool` methods the model can use to reason about
the fleet itself:

- `find_fleet_agents(question, limit)`: semantic discovery over AgentCards
  through `SemanticClient.remoteQuery`, returning each match's name, description,
  input and output types, the name of the F1 tool that invokes it, and the match
  score. The model can then call that tool directly.
- `fetch_fleet_data(asset, parameters)`: a `DataQueryClient.fetch` over the
  connector SDK, so a model reads enterprise data through the space with
  pull-once caching, and a second agent asking the same question gets the
  leased result without touching the source.
- `list_fleet_assets(question)`: semantic discovery over AssetCards, so the
  model can find which asset to fetch.

Each tool is enabled separately, because each widens what the model can reach.
Data results are capped in size before they enter the model's context
(`discovery-tools.max-result-chars`).

### F3. Crash-safe LLM workers

An agentic tool loop can run for minutes. Under `@SpaceTake`, the take lease
must outlast the loop, or the task reappears while the first worker is still
working on it; a lease long enough for the worst case delays recovery when a
worker really dies. F3 resolves this, and makes the conversation itself
survive a reassignment.

**`TakeContext` (core change).** The `@SpaceTake` and `@OrderedTake` loops in
`AgentBinder` set a `TakeContext` around each method call: the in-flight
`TakenEntry`, the binding's lease duration, the entry ID, and the bound agent's
ID. `TakeContext.current()` returns it on the loop's thread. Spring AI's
streaming path runs on Reactor threads, so the project registers a Micrometer
`ThreadLocalAccessor` for `TakeContext`, and Reactor's automatic context
propagation carries it onto those threads (section 7.1).

**`TakeLeaseAdvisor`.** A `CallAdvisor` and `StreamAdvisor`, ordered after
`ToolCallingAdvisor` so it runs inside the tool loop, once per model round-trip.
On each pass it renews the current take lease by the binding's lease duration
through `TakenEntry.renew(Duration)`. A streaming round-trip also renews at a
fixed interval while chunks arrive. A worker that is making progress keeps its
claim; a worker that crashes or hangs stops renewing, and its task reappears one
lease later. Leases can then be short (two minutes), which makes recovery fast.
The advisor does nothing when no take is in flight, so the autoconfiguration
adds it to every auto-configured `ChatClient.Builder` through a
`ChatClientBuilderCustomizer` bean by default.

**`SpaceChatMemoryRepository`.** A `ChatMemoryRepository` backed by a replicated
space (`conversations` by default), so a conversation outlives the process that
started it.

- `saveAll(conversationId, messages)` writes a `ConversationSnapshot` entry with
  a version one higher than the current one, and cancels the previous snapshot,
  which matches `MessageWindowChatMemory`'s replace-the-window semantics.
- `findByConversationId` reads the highest version; `findConversationIds` and
  `deleteByConversationId` complete the interface.
- Snapshots are leased (`memory.ttl`, 24 hours by default), so abandoned
  conversations leave the space on their own.
- Snapshots larger than 64 KiB travel content-addressed over the block exchange.
- **Options for the conversation ID.** `memory.conversation-id` selects the
  default: `take` (the in-flight task's entry ID, from `TakeContext`, so a
  reappeared task resumes with the first worker's conversation), `agent` (the
  bound agent's ID, so an agent keeps one long-running memory across tasks), or
  `explicit` (the caller always passes `ChatMemory.CONVERSATION_ID`). A
  `ConversationIdResolver` bean replaces the rule entirely.
- `FleetChatMemoryAdvisor` wraps `MessageChatMemoryAdvisor` and fills in the
  resolved conversation ID, so worker code does not thread the ID through.

Spring AI does not store tool-call intermediate messages in chat memory, so a
resumed conversation replays the user and assistant turns, and the tools run
again if the model asks for them. The design recommends idempotent tools, or
`@OrderedTake` for non-idempotent work (example 12).

### F4. Model access as a fleet service

`FleetChatModel implements ChatModel` (and so `StreamingChatModel`) sends each
model round-trip through the space. `ModelServer` runs on the peers that hold
provider credentials and serves those requests with their real `ChatModel`s.

```java
// caller: no API key, no provider dependency
ChatClient chat = ChatClient.builder(fleetChatModel).defaultToolCallbacks(localTools).build();
Flux<String> answer = chat.prompt().user("Plan the offsite").stream().content();
```

```yaml
# server peer: holds the credentials, serves named models
agentspaces:
  springai:
    model-server:
      enabled: true
      models: [gpt-4.1-mini, claude-sonnet-5]
```

The design relies on a Spring AI 2.0 change: `ChatModel.call` performs exactly
one model round-trip and never runs tools, because the tool loop lives in
`ChatClient`. `FleetChatModel` therefore carries one request and one response.
Tool calls come back inside the `AssistantMessage`, and the caller's own
`ToolCallingAdvisor` executes them locally, with the caller's own tools. The
server never runs the caller's tools.

**Wire records**, designed for the canonical CBOR codec (plain strings,
numbers, lists, and maps):

```java
record ModelRequest(String requestId, String model, List<WireMessage> messages,
                    WireOptions options, List<WireToolDefinition> tools,
                    boolean stream, String requester) { }
record ModelResponse(String requestId, String model, List<WireGeneration> generations,
                     WireUsage usage, String error, String servedBy, int attempt) { }
record ModelChunk(String requestId, int attempt, int seq, List<WireGeneration> deltas,
                  boolean last, WireUsage usage, String servedBy) { }
record ModelCancel(String requestId, String requester) { }
record WireMessage(String role, String text, List<WireToolCall> toolCalls,
                   String toolCallId, String toolName, List<WireMedia> media) { }
record WireMedia(String mimeType, String cid, String url) { }
```

**Crash tolerance.** `FleetChatModel.call` writes a `ModelRequest` into the
`model-requests` space and awaits the `ModelResponse` by `requestId`, using the
subscribe-then-write pattern of `RemoteAction.invoke`. `ModelServer` is a
`@SpaceTake` worker over `ModelRequest` that completes the take with the
response. A server that dies mid-call lets its lease lapse, and another server
takes the request. `ModelServer` renews its own take lease while the provider
call runs, so the lease can stay short.

**Routing.** A server advertises the models it serves on its AgentCard, with
model names and per-token prices as cost hints. Two routing modes exist:

- **By model** (default, LEASE_RACE): a server takes only requests for the
  models it serves, through a template on the `model` field.
- **By price** (AUCTION): the requests space runs AUCTION, and each server's
  `@BidFunction` prices the request from its token price, an estimate of the
  prompt size, and its current load. The fleet routes each call to the cheapest
  server that serves the model, and a server that does not serve the model bids
  `+Infinity`.

**Media.** Images, audio, and documents in a `Prompt` map to `WireMedia`. Media
up to 64 KiB rides inline; larger media travels content-addressed by CID over
the block exchange, so the request entry stays small, and the server pulls the
block when it reads the request. URL media passes through as a URL.

**Streaming.** When the caller uses `stream()`, the request carries
`stream=true`, and the server calls its model's `stream(Prompt)`:

1. The client subscribes to `ModelChunk` entries for the `requestId` before it
   writes the request, and exposes them as a `Flux<ChatResponse>`.
2. The server batches deltas into chunks by time and size (100 ms or 32 deltas by
   default), so a long response writes tens of entries instead of thousands.
   Each chunk carries `attempt`, `seq`, and its deltas; the last chunk carries the
   token usage and `last=true`.
3. Chunks are short-leased (two minutes), so they collect themselves. The server
   also completes the take with the full `ModelResponse`, so a late reader, or a
   caller that switches to `call`, reads the whole answer.
4. The client emits chunks strictly in `seq` order. Gossip can deliver chunks out
   of order, so the client holds a reorder buffer, and a read-through over the
   space fills any gap that notifications missed, the same backstop
   `RemoteAction.invoke` uses.
5. **Cancellation.** When the subscriber cancels, the client writes a
   `ModelCancel`, and the server stops streaming and completes the take with what
   it has.
6. **Crash mid-stream.** Model generation is not deterministic, so a restarted
   stream cannot continue where the first one stopped. When a server dies, its
   lease lapses and another server takes the request with a higher `attempt`. The
   client's `stream.on-restart` policy decides what happens:
   `fail` (the default) ends the `Flux` with a `FleetStreamRestartedException`
   that carries the partial text, so the caller can retry or keep the partial
   answer; `restart` emits a marker and then the new attempt's chunks from the
   beginning, for consumers that can discard the partial output, such as a UI.
   The `call` path has no such caveat: a restart there is invisible to the
   caller.
7. **Tool calls in a stream.** Tool-call deltas travel in the chunks, and the last
   chunk carries the complete tool calls, which Spring AI's streaming tool loop
   then executes on the caller.

**Usage.** Every `ModelResponse` and every last chunk carries token usage, so
the caller's `ChatResponseMetadata.getUsage()` and its observations stay
accurate.

**What F4 leaves out.** Deduplication against a response cache, budgets, and
cross-provider routing policies belong to the enterprise `aspace:cap/llm-gateway`
and `aspace:cap/result-cache`. They can layer onto the same wire records and the
same `ModelServer` without changing the open-source protocol.

### F5. Real embeddings for semantic discovery

`SpringAiEmbedder implements Embedder` adapts an `EmbeddingModel` to the
two-method `Embedder` SPI: it converts the model's `float[]` to `double[]`,
L2-normalizes it, and reports the model's dimensions. It caches embeddings of
advertisement text by content hash, because `SemanticDiscovery.query` re-embeds
every indexable advertisement on each call.

**The embedder on the capability advertisement.** Every peer that answers a
remote semantic query ranks with its own embedder, so a fleet must use one
embedding model everywhere. The semantic-discovery `CapabilityAdvertisement`
carries the embedder's identity in its parameters: `embedder` (for example
`spring-ai:text-embedding-3-small` or `hashing`), `dimensions`, and `normalized`.
A peer compares its own embedder with every neighbor's advertisement, logs a
warning on a mismatch, and sends remote queries only to peers whose embedder
matches its own (`embedder.require-match=true`, the default). The console shows
each peer's embedder.

Both changes sit in the core: the embedder identity on the advertisement in
`agentspaces-capabilities`, and an `Embedder` bean in the autoconfiguration
(section 10).

### F6. Fleet-wide usage

`FleetUsageObservationHandler` is a Micrometer `ObservationHandler` for the
`gen_ai.client.operation` observation. It covers every model call in the
process, including calls that bypass `ChatClient`, and it feeds token usage into
the fleet in three forms:

- a push-sum aggregate per model (`tokens.<model>.input`, `.output`), so any peer
  can read the fleet's total and average usage with no collector;
- an optional `ModelUsage` entry per call in a leased `metrics` space, attributed
  to the agent that made the call (from `TakeContext` when a take is in flight),
  for audit;
- a `ConsolePanel` bean that shows usage per model and per agent in the fleet
  console.

Budgets that refuse calls over a limit belong to the enterprise gateway.

### F7. MCP export

Spring AI's MCP server starter publishes `ToolCallbackProvider` beans as MCP
tools. A peer that runs the starter alongside this project exposes the fleet's
F1 and F2 tools to any MCP client, such as Claude Desktop or an IDE agent, over
Streamable HTTP. The MCP server peer then acts as a gateway, much like the A2A
gateway in `agentspaces-a2a`.

The fleet's tool set changes as cards arrive and lapse, and the starter may
register tools once at startup (verify). `FleetMcpToolSync` keeps the MCP
server's tools in step with the fleet: an `@EventListener` for F1's
`FleetToolsChangedEvent` receives the added and removed tools, applies them to
the `McpSyncServer`, and sends MCP's
`notifications/tools/list_changed` so connected clients refresh their tool list
(verify the 2.0 API names). A property limits which fleet tools the MCP server
exposes, separately from the in-process filters, because an MCP client sits
outside the fleet's trust boundary.

### F8. Patterns with existing annotations

The project's README and examples cover patterns that combine existing
AgentSpaces annotations with Spring AI, with no new core API:

- **A panel of judges:** a `@Ballot` method runs a `FactCheckingEvaluator` or a
  `ChatClient` over the proposal and returns its verdict, so a panel of models
  votes with signed, auditable ballots, and `@OnDecision` accepts the result.
- **Priced by tokens:** a `@BidFunction` prices a task from the model's token price
  and an estimate of the prompt size, so AUCTION routes each task to the cheapest
  capable model.
- **Extraction ensemble:** several `@SpaceNotify` extractors over different models
  write candidates, and a vote reconciles them (example 08's shape with real
  models).
- **A shared knowledge base:** `VectorStoreAssetProvider` exposes a Spring AI
  `VectorStore` as an `AssetProvider`, so agents query it through the connector
  SDK with leased, pull-once results.

## 7. Project structure

```
agentspaces-springai/                    standalone project at the workspace root
  pom.xml                                aggregator: BOM imports, plugin config
  core/                                  the library and its autoconfiguration
    src/main/java/ai/badmonkey/agentspaces/springai/
      tools/          FleetToolCallbackProvider, RemoteActionToolCallback,
                      FleetDiscoveryTools, FleetToolsChangedEvent,
                      FleetToolFilter, ToolNamingStrategy (+ defaults)
      worker/         TakeLeaseAdvisor, TakeContextAccessor
      memory/         SpaceChatMemoryRepository, ConversationSnapshot,
                      FleetChatMemoryAdvisor, ConversationIdResolver (+ default)
      model/          FleetChatModel, ModelServer, ModelStreamAssembler,
                      ModelCatalog, ModelRequestRouter, StreamChunkingPolicy,
                      StreamRestartPolicy (+ defaults), WireMapping
      model/wire/     ModelRequest, ModelResponse, ModelChunk, ModelCancel,
                      Wire* records (plain Java, no Spring types)
      embed/          SpringAiEmbedder
      usage/          FleetUsageObservationHandler, UsageSink (+ default), UsagePanel
      mcp/            FleetMcpToolSync
      connect/        VectorStoreAssetProvider
      autoconfigure/  AgentSpacesSpringAiProperties, FleetClientCustomizer,
                      FleetToolsAutoConfiguration, FleetMemoryAutoConfiguration,
                      FleetModelClientAutoConfiguration,
                      FleetModelServerAutoConfiguration,
                      FleetEmbeddingAutoConfiguration, FleetUsageAutoConfiguration,
                      FleetMcpAutoConfiguration
    src/main/resources/META-INF/spring/
      org.springframework.boot.autoconfigure.AutoConfiguration.imports
  examples/
    springai-fleet/                      example 15: a Spring AI orchestrator on the fleet
    springai-patterns/                   F8: judges, token-priced bids, ensembles
```

**Dependencies.** The project imports three BOMs: `spring-boot-dependencies`
4.1.x, `spring-ai-bom` 2.0.1, and `agentspaces-dependencies` 0.2.0. It
depends on the real Spring and Spring AI APIs directly, at compile scope,
because using them keeps the code smaller and makes the project read like any
other Spring library. The library depends on:

- `spring-boot-autoconfigure` and `spring-context`, with
  `spring-boot-configuration-processor` (optional) generating property
  metadata for IDE completion;
- `org.springframework.ai:spring-ai-model` and `spring-ai-client-chat`;
  `spring-ai-vector-store` and the Spring AI MCP server module as optional
  dependencies for F8 and F7;
- `io.projectreactor:reactor-core` and `io.micrometer:context-propagation` for
  streaming, and `io.micrometer:micrometer-observation` for F6 and the
  project's own observations;
- `agentspaces-spring-boot-starter`, `agentspaces-agent`,
  `agentspaces-capabilities`, `agentspaces-connect-core`, `agentspaces-console`;
- `spring-boot-starter-test` and `agentspaces-test-support` at test scope.

The core reactor keeps its rule of compiling against stubs, so that it builds
and runs with no Spring on the path. This project sits outside the reactor
precisely so it can take the opposite position and embrace Spring fully. It
compiles against published AgentSpaces artifacts (`mvn install` of the core
first, as for the Party Bus and the flagships).

### 7.1 The Spring programming model

The project follows Spring's program-by-interface and dependency-injection
style throughout, and stays small and open: a handful of focused classes, each
behind an interface a deployment can replace.

**Program to interfaces.** Every decision a deployment might want to change is
an interface with one default implementation, registered as a
`@ConditionalOnMissingBean` bean. Declaring a bean of the interface replaces
the default, with no subclassing and no property flags:

| Extension point | Default implementation | Replace it to |
| --- | --- | --- |
| `FleetToolFilter` | include and exclude lists and `require-attested` from properties | apply a custom trust rule to which cards become tools |
| `ToolNamingStrategy` | `fleet_` prefix, sanitized, truncated, deduplicated | follow an organization's tool naming convention |
| `ConversationIdResolver` | the `memory.conversation-id` option (`take`, `agent`, `explicit`) | key conversations by tenant, user, or session |
| `ModelCatalog` | every `ChatModel` bean named in `model-server.models` | serve models from a registry or per-tenant configuration |
| `ModelRequestRouter` | by model name (LEASE_RACE), or by price (AUCTION) | route by tenant, region, or load |
| `StreamChunkingPolicy` | 100 ms or 32 deltas | tune batching for latency or entry count |
| `StreamRestartPolicy` | `fail` or `restart` | apply a custom recovery, such as resuming with a continuation prompt |
| `UsageSink` | push-sum aggregates, plus optional `ModelUsage` entries | export usage to a billing or FinOps system |

Spring AI's own interfaces are the rest of the public API: the project's
components *are* a `ToolCallbackProvider`, a `ChatMemoryRepository`, a
`ChatModel`, a `CallAdvisor` and `StreamAdvisor`, and an `ObservationHandler`,
so an application uses them exactly as it uses any Spring AI implementation.

**Dependency injection.**
- Constructor injection only, with final fields and no static state, so every
  component can also be built with `new` in a plain unit test.
- Optional collaborators arrive through `ObjectProvider<T>`, and the model
  catalog receives the application's `ChatModel` beans as a `Map<String, ChatModel>`.
- Components depend on the AgentSpaces beans the starter already defines (the
  `AgentSpaces` facade, `PeerIdentity`, the `Authorizer`), never on the peer's
  internals.

**Configuration.** One `@ConfigurationProperties("agentspaces.springai")`
record with nested records per feature, bound by constructor, using `Duration`
and `DataSize` types and `@DefaultValue`, and validated with `@Validated`. The
configuration processor generates the metadata, so every property in section 8
completes in the IDE.

**Focused auto-configurations.** Following Spring AI's own modular layout,
each feature has its own small `@AutoConfiguration` class, ordered after the
core `AgentSpacesAutoConfiguration`, conditional on the classes it needs
(`@ConditionalOnClass`) and on its property (`@ConditionalOnProperty`):
`FleetToolsAutoConfiguration`, `FleetMemoryAutoConfiguration`,
`FleetModelClientAutoConfiguration`, `FleetModelServerAutoConfiguration`,
`FleetEmbeddingAutoConfiguration`, `FleetUsageAutoConfiguration`, and
`FleetMcpAutoConfiguration`. An application that excludes one feature's
auto-configuration keeps the others.

**Spring mechanisms over hand-written ones.** Where the core uses its own
threads and hooks, this project uses the Spring equivalent:

| Need | Spring mechanism |
| --- | --- |
| Add `TakeLeaseAdvisor` and `FleetChatMemoryAdvisor` to every `ChatClient` | a `ChatClientBuilderCustomizer` bean, which Spring AI applies to the auto-configured `ChatClient.Builder` (`ChatClientCustomizer` is deprecated in 2.0.1) |
| Carry `TakeContext` onto Reactor threads | a Micrometer `ThreadLocalAccessor` registered in the `ContextRegistry`, so Reactor's automatic context propagation carries it with no manual capture |
| Start and stop `ModelServer` and `FleetMcpToolSync` | `SmartLifecycle`, phased after the core's `AgentSpacesLifecycle` |
| Periodic work (tool-set diffs, stream lease renewal) | the auto-configured `TaskScheduler`, on virtual threads when `spring.threads.virtual.enabled=true` |
| React when the fleet's tools change | a `FleetToolsChangedEvent` published through `ApplicationEventPublisher`; `FleetMcpToolSync` and the console listen with `@EventListener` |
| Register the usage handler | an `ObservationHandler` bean, which Spring Boot adds to the `ObservationRegistry` |
| Trace fleet calls | the project's own observations (`agentspaces.fleet.tool`, `agentspaces.model.request`) on the injected `ObservationRegistry`, so fleet hops appear in traces beside Spring AI's spans |
| JSON for tool input and output | the auto-configured Jackson 3 `JsonMapper`, the same mapper Spring AI uses |
| Test the auto-configuration | `ApplicationContextRunner`, for every condition and override |

**Light and open.** The wire records (`ModelRequest`, `ModelChunk`, and the
rest) are plain Java records with no Spring types, encoded by the core's
canonical CBOR codec, so the fleet-facing protocol stays language neutral: a
Python or TypeScript peer can serve or send model requests without Spring. The
project defines no base classes to extend and no annotations of its own, and
each class has one responsibility.

## 8. Configuration

All properties sit under `agentspaces.springai`. Each feature that widens what
a model can reach, or that sends prompts across the fleet, defaults off.

| Property | Default | Meaning |
| --- | --- | --- |
| `tools.enabled` | `true` | Register `FleetToolCallbackProvider` as a bean; applications attach it to a `ChatClient` |
| `tools.group` | first group | The group whose cards become tools |
| `tools.prefix` | `fleet_` | Tool name prefix |
| `tools.include-agents`, `tools.exclude-agents` | empty | Filters by agent local name or AgentId |
| `tools.require-attested` | `false` | Only `AGENT_ATTESTED` cards become tools |
| `tools.timeout` | `30s` | How long one fleet tool call waits for its result |
| `tools.on-timeout` | `result` | `result` returns an explanatory tool result; `throw` raises |
| `discovery-tools.agents`, `.data`, `.assets` | `false` | Register each `FleetDiscoveryTools` method |
| `discovery-tools.max-result-chars` | `20000` | Cap on data returned to the model |
| `take-lease.enabled` | `true` | Add `TakeLeaseAdvisor` to every auto-configured `ChatClient.Builder` through a `ChatClientBuilderCustomizer` |
| `take-lease.stream-renew-interval` | `30s` | Renewal interval while a stream runs |
| `memory.enabled` | `false` | Register `SpaceChatMemoryRepository` and `FleetChatMemoryAdvisor` |
| `memory.space` | `conversations` | The space that holds conversation snapshots |
| `memory.ttl` | `24h` | Snapshot lease |
| `memory.conversation-id` | `take` | `take`, `agent`, or `explicit` |
| `model-client.enabled` | `false` | Register a `fleetChatModel` bean (never `@Primary`) |
| `model-client.space` | `model-requests` | Where requests are written |
| `model-client.timeout` | `120s` | How long one round-trip waits |
| `model-client.stream.on-restart` | `fail` | `fail` or `restart` when a server dies mid-stream |
| `model-server.enabled` | `false` | Serve model requests with this peer's `ChatModel` beans |
| `model-server.models` | empty | The model names this peer serves |
| `model-server.routing` | `model` | `model` (LEASE_RACE by model name) or `price` (AUCTION) |
| `model-server.lease` | `2m` | Take lease, renewed while a call runs |
| `model-server.chunk-interval`, `.chunk-max-deltas` | `100ms`, `32` | Stream batching |
| `embedder` | `hashing` | `hashing` or `spring-ai`; every peer in a group must agree |
| `embedder.require-match` | `true` | Query only peers that advertise the same embedder |
| `usage.enabled` | `false` | Register `FleetUsageObservationHandler` |
| `usage.metrics-space` | empty | Also write a `ModelUsage` entry per call to this space |
| `mcp.enabled` | `false` | Keep the MCP server's tools in step with the fleet |
| `mcp.include-agents` | empty | Fleet tools the MCP server may expose (empty exposes none) |
| `vector-store.enabled` | `false` | Serve the application's `VectorStore` bean as a fleet data asset through `VectorStoreAssetProvider` |
| `vector-store.space`, `.asset`, `.description`, `.freshness` | `data`, `knowledge-base`, a generic description, `5m` | The data space, asset name, discovery text, and result lease |

## 9. Security

- **Prompts cross the fleet under F3 and F4.** Conversation snapshots, model
  requests, and model chunks replicate to every member of the group. A deployment
  that uses them should give those spaces their own admission (`allowlist`,
  `credential`, or `authorizer`) and a group content key, so that only the
  intended peers can read prompts and responses. The project's README states this
  on its first page.
- **Only trusted servers answer.** `FleetChatModel` accepts a `ModelResponse` or
  `ModelChunk` only from an issuer the `Authorizer` permits to serve models. That
  needs a new operation, `MODEL_SERVE`, beside the existing seven, with an
  `agentspaces.security.grants.model-serve` list and an `aspace:model-serve`
  scope under the OIDC profiles. This is a SPEC §11 change (section 10).
- **Tool descriptions are prompt input.** A card's description flows into the
  model's context, which makes a malicious card a prompt-injection vector.
  `RemoteAction` already strips control characters and caps length (ASF-030); the
  project adds include and exclude filters and `tools.require-attested`.
- **Tool input is validated and attributed.** The model's JSON is deserialized
  into the declared record type before anything is written, and the write carries
  the calling agent's identity, so every fleet tool call is signed.
- **Credentials stay on server peers.** Under F4, provider API keys live only on
  `ModelServer` peers.
- **The MCP boundary.** MCP clients sit outside the fleet. `mcp.include-agents`
  exposes nothing until a deployment names the agents it will expose, and the MCP
  server's own authentication governs who may connect.

## 10. Changes outside the project

| Change | Where | For |
| --- | --- | --- |
| `TakeContext.current()` set by the `@SpaceTake` and `@OrderedTake` loops | `agentspaces-agent` | F3, F6 attribution |
| An `Embedder` bean, `@ConditionalOnMissingBean`, defaulting to `HashingEmbedder`, used by `registerCapabilities` | `agentspaces-spring-boot-autoconfigure` | F5 |
| Embedder identity (`embedder`, `dimensions`, `normalized`) on the semantic-discovery advertisement, and matching-embedder routing for remote queries | `agentspaces-capabilities`, SPEC §8 | F5 |
| `Authorizer.Operation.MODEL_SERVE`, its grant list, and its OIDC scope | `agentspaces-api`, `agentspaces-peering`, `agentspaces-auth-oidc`, starter properties, SPEC §11 | F4 |
| The Boot 4, Spring Framework 7, and Embabel 1.5 upgrade | core reactor, Party Bus, `verify-all.sh` | The platform upgrade (section 13) |

The operation list and advertisement parameters are not fixed wire structures,
so no golden vector should change; the three conformance suites (Java, Python,
TypeScript) confirm it.

## 11. Remaining follow-ups

1. **Spring AI Agents.** The experimental Spring AI Agents project is due in
   November 2026. Review the design against it when it appears, since it may
   define agent and delegation abstractions that fleet tools should implement.
2. **Spring AI 2.1.** 2.1 introduces a message-parts model (`MessagePart`,
   `ToolCallPart`, `ReasoningPart`). The wire records should gain reasoning parts
   when 2.1 goes GA.
3. **Enterprise gateway.** When `aspace:cap/llm-gateway` is built, confirm it
   layers onto F4's wire records without an open-source protocol change.

## 12. Sources

Spring AI (fetched 2026-09-30):
- Release notes: [2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/) (2026-06-12), [2.1.0-M1](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/) (2026-09-25), [releases](https://github.com/spring-projects/spring-ai/releases)
- Reference, 2.0.1: [ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html), [chat model](https://docs.spring.io/spring-ai/reference/api/chatmodel.html), [advisors](https://docs.spring.io/spring-ai/reference/api/advisors.html), [tools](https://docs.spring.io/spring-ai/reference/api/tools.html), [tool calling advisor](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html), [chat memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html), [MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html), [MCP server annotations](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html), [observability](https://docs.spring.io/spring-ai/reference/observability/index.html), [effective agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html), [upgrade notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)

Embabel (fetched 2026-09-30):
- [embabel-agent](https://github.com/embabel/embabel-agent) (1.5.2 on Maven Central, 2026-09-16), [user guide](https://docs.embabel.com/)

AgentSpaces:
- `agentspaces/embabel-agentspaces/`, `agentspaces/agentspaces-agent/` (`AgentBinder`, `RemoteActions`), `agentspaces/agentspaces-spring-boot-autoconfigure/`, `agentspaces/agentspaces-spring-stubs/`, `agentspaces/agentspaces-connect-core/`, `agentspaces/integration-tests/spring-boot-it/`, `agentspaces-partybus/`, `verify-all.sh`
- `ENTERPRISE.md` (llm-gateway, result-cache), `COMPETITIVE.md` §5 and §6, `WHY.md` (the model-gateway peer), `USE-CASES.md` §4

## 13. Implementation record

The whole plan shipped: phase 0 (the platform upgrade) and F1 through F8, each
with the tests its plan named. The step-by-step implementation plan, once
section 11, is removed now that every task in it is verified done; this section
records where the build departed from sections 1 to 11, and why, so the design
stays accurate.

**Platform.**
- Spring Boot 4.1.1, Spring Framework 7.0.9, Spring AI 2.0.1, Embabel 1.5.2.
- The Spring stubs needed no change: every stubbed name is unchanged in Boot 4.1.
- The core's Jackson 2 pin moved from 2.19.0 to 2.21.5, the version Boot 4.1
  manages, through `jackson-bom` (since 2.20, `jackson-annotations` carries a
  major.minor version only). The golden vectors are byte-identical on 2.21.5, so
  nothing was regenerated.
- Jackson then moved to 2.21.7 and 3.1.7 (2026-10-02) for security advisories
  fixed since Boot 4.1.1's 2.21.5 and 3.1.5. This project imports both Jackson
  BOMs ahead of the Boot and Spring AI BOMs, so the patched versions win; the
  golden vectors are byte-identical on them too. Drop the override once Boot
  manages 3.1.7 or later.
- Byte Buddy moved to 1.18.11 (Boot's version, which supports Java 25).
- `integration-tests/embabel-it` is the first test against a real Embabel
  artifact: Embabel's GOAP planner reaches a goal through a fleet action.
- `agentspaces-dependencies` now lists every published module.
- `verify-all.sh` builds Party Bus under `-Pembabel`, the Clojure bindings, and
  this project.

**Spring AI facts the design depended on (the "verify" items).**
- `ChatClient` reads a `ToolCallbackProvider` on every request, so the live tool
  set needs no special handling.
- `ChatClient` attaches tools only when `chatModel.getOptions()` returns
  tool-calling options; `FleetChatModel` does, and the test fakes must too.
- `ChatClientCustomizer` is deprecated; the auto-configuration also applies
  `ChatClientBuilderCustomizer`, which this project uses.
- Spring AI caps each tool at 40 calls per loop (`DEFAULT_MAX_CALLS_PER_TOOL`);
  long tool loops should expect it.

**Departures.**
- **`FleetTools` is not a `ToolCallbackProvider` bean.** Spring AI's MCP server
  auto-configuration publishes every `ToolCallbackProvider` bean to MCP clients,
  which would have exposed the fleet whenever the MCP starter was present. The
  beans are `FleetTools` and `FleetDiscoveryTools`, and applications attach
  `toolCallbacks()` to a `ChatClient` explicitly; F7 remains the only path to MCP.
- **Tools route by entry type.** A fleet tool writes its input as a task, and any
  agent that takes that type may perform it; the MCP flow test found this when a
  second agent consuming the same type answered. Tool descriptions now say so, and
  the README tells deployments to give private agents their own request types or
  restrict `SPACE_TAKE`.
- **`fleetChatModel` is a `@Fallback` bean.** On a node with no provider it is the
  `ChatModel` Spring AI's `ChatClient` uses; beside a provider, the provider wins
  injection by type and the fleet model is injected by name.
- **Stream attempts** are counted with `ModelAttempt` entries a server writes on
  each take, so a blocking call's response also reports its attempt
  (`agentspaces.attempt` in the response metadata, with `agentspaces.servedBy`).
- **Media** travels content-addressed at the entry level: the space
  content-addresses any entry over 64 KiB, and refuses one that cannot be, so
  the media test proves the CID path without a per-media CID field.
- **Properties** validate in each record's compact constructor, with no Bean
  Validation dependency. The embedder type is `embedder.type`, since a property
  cannot be both a value and a group.
- **New properties:**
  - `tools.refresh-interval` and `tools.cache-ttl`;
  - `discovery-tools.data-space` and `discovery-tools.timeout`;
  - `memory.max-messages`;
  - `model-client.model`;
  - `model-server.chunk-lease`, `response-lease`, `concurrency`, and
    `model-beans` (for nodes with several providers);
  - `embedder.model` and `embedder.cache-size`;
  - `usage.entry-lease` and `usage.publish-interval`;
  - `mcp.discovery-tools`.
- **Fleet usage totals** use push-sum SUM epochs named by model and time window,
  which every peer derives the same way, joined on `usage.publish-interval`.
- **The patterns** assemble peers with the core API, like the numbered examples,
  so each multi-peer pattern stays in one file; example 15 shows the Spring Boot
  shape.
- **Discovery tools take functions, not clients.** `FleetDiscoveryTools` is
  built from two functional interfaces, `Search` and `Fetch`, which the
  auto-configuration backs with `SemanticClient` and `DataQueryClient`; unit
  tests pass lambdas, and the flow test runs the real clients.
- **`find_fleet_agents` names tools from F1's live callbacks**, not through
  `ToolNamingStrategy` again, so the names it returns are the deduplicated names
  the model can actually call.
- **The data cap is `discovery-tools.max-result-chars`**, an integer count of
  characters (what reaches the model), not a `DataSize`.
- **Price routing installs the bid function directly.** `ModelServer.start()`
  sets `ReplicatedSpace.bidFunction(...)` from the `ModelRequestRouter`, rather
  than binding a `@BidFunction` bean, so routing stays one extension point.
- **The stream is `Flux.create` with a buffer**, not `Sinks`: chunks arriving
  ahead of demand wait in the buffer, and the subscriber receives only what it
  requests, in order (`FleetChatModelLocalTest`).
- **The vector store is served by its own auto-configuration.**
  `FleetVectorStoreAutoConfiguration` (`vector-store.enabled`, off by default)
  builds `VectorStoreAssetProvider` over the application's `VectorStore` bean
  and serves it with a `VectorStoreConnector`, a `SmartLifecycle` over the
  connector SDK's `ConnectorRuntime` that starts after the AgentSpaces
  lifecycle. The store is looked up when the provider is built rather than
  required by `@ConditionalOnBean`, since a vector store's own auto-configuration
  may run later; enabling the feature without one fails at startup.
- **The two-application model test runs in the default build.** Every test in
  this project boots real Spring, so `FleetModelSpringBootTest` needs no
  `spring-it` profile: the caller is the `@SpringBootTest` context, with its own
  provider beside `fleetChatModel`, and the server is a second Boot application
  started before it.
- **No separate MCP spike test.** `FleetMcpExportFlowTest` runs the runtime
  tool API (`addTool`, `removeTool`, `notifyToolsListChanged`) against the real
  MCP server over Streamable HTTP, which is what the spike was to establish.
- **Test shapes.** Several tests prove their task with a different setup than
  the plan sketched: Boot contexts started with `SpringApplicationBuilder`
  rather than `@SpringBootTest` (the examples, the flow tests); the crash-safe
  worker test with two workers and a 2-second lease rather than three workers
  and a two-minute claim; the take-lease advisor's stream renewals on a real
  scheduler rather than `TestClock`; the tool provider's unit tests over a fake
  action supplier rather than a hand-built discovery cache; and pull-once
  across two agents proven over the vector store (`VectorStoreAssetProviderFlowTest`)
  and with one agent asking twice over a table asset.
- **SPEC v0.1.12** records the embedder identity on semantic discovery (§8) and
  `MODEL_SERVE` (§11); no golden vector changed.

**Audits against the plan.** A first pass over the design (sections 7 to 10)
and the plan found every feature, extension point, property, and
auto-configuration condition built as planned, and five test commitments from
the plan unmet. All five are now closed:
- `FleetChatModelLocalTest` checks the streamed flux with `StepVerifier`, in
  order despite out-of-order and duplicated chunks, and checks that cancelling it
  writes a `ModelCancel`.
- The same test asserts the `agentspaces.model.request` observation through
  `TestObservationRegistry`, and that a stopped `ModelServer` takes no requests.
- A conversation larger than the 64 KiB inline limit round-trips through
  `SpaceChatMemoryRepository`, content-addressed.
- Opt-in live-model tests, skipped unless `AGENTSPACES_LIVE_MODELS=1` and
  `OPENAI_API_KEY` are set: `LiveModelServiceTest` serves a real OpenAI model
  through `ModelServer` and calls it through `FleetChatModel`, blocking and
  streamed, and Party Bus's `LiveSourcesLiveTest` fills a record from a real model.

The audit also hardened `CrashSafeWorkerFlowTest`. Spring AI hands a tool's
exception back to the model as a tool result, so a worker interrupted by its
context closing could run out its loop against the call cap and complete the task
before its space closed. The test's first worker now fails at its model once
killed, as a crashed process would.

A second, task-by-task pass (2026-10-03) read every task's code and test and
found what the first pass missed. Each is now closed:
- **The vector store had no bean.** It is now auto-configured and served
  (`FleetVectorStoreAutoConfigurationTest`: off by default, a query through the
  data space answered from the store, fail-fast without a store or its space,
  back-off without the vector store module, and an application provider bean).
- **The two-application model test was missing.** `FleetModelSpringBootTest`
  injects `fleetChatModel` by name beside the caller's own provider, which stays
  the `ChatModel` by type, and the other application serves both a call and a
  stream.
- **`FleetMcpToolSync` had no unit test.** `FleetMcpToolSyncTest` drives it with
  fleet changes against a mock `McpSyncServer`: the include filter, adds,
  removes, no notification when nothing changed, extra tools, and stop.
- **`TakeContext` was untested in the ordered-take loop.** The `@OrderedTake`
  cluster test now asserts each confirmation runs inside its own take.
- **`ModelCatalog` had no unit test.** `ModelCatalogTest` covers the default,
  `model-beans` routing, and each startup error.
- **Streams lacked three proofs.** `FleetChatModelLocalTest` now shows a chunk
  whose notify event is lost arriving by read-through, in order; a subscriber
  getting only what it requests; and a cancel after three chunks stopping the
  server's provider while the server still completes with a last chunk and the
  partial answer.
- **The Spring conventions were partly tested.** `AutoConfigurationConventionsTest`
  adds, per feature, the back-off when its classes are absent and a user bean
  replacing each extension point not yet covered, and enables each discovery
  tool alone.
- **The live-model tests failed without a key.** Both now also require
  `OPENAI_API_KEY`, so they skip unless it is set.

**Follow-ups.**
1. Closed: the starter's and the Embabel extension's bean post-processors are now
   static `@Bean` methods over `ObjectProvider`s, which ends Boot 4's
   `BeanPostProcessorChecker` warnings.
2. Closed: the Party Bus guide generator follows the annotated Party Bus, and the
   guide is regenerated.
3. Open: review against Spring AI Agents (November 2026) and Spring AI 2.1's
   message parts, as section 11 notes.
