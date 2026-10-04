# Example 15: Spring AI Fleet

A Spring AI application on the fleet. Two worker applications, a summarizer
and a translator, are ordinary Spring AI code: a `@SpaceAgent` bean with an
injected `ChatClient.Builder` and one `@SpaceTake` method. A third application,
the orchestrator, attaches the fleet's tools to its `ChatClient`, and its model
calls both workers, on other peers, from its own tool loop. No application knows
another's address or name; the workers' AgentCards are the tool catalog.

This example continues the core examples (01 to 14 in `agentspaces/examples`),
and is the first that uses Spring AI and `agentspaces-springai`.

## What it demonstrates

- **Fleet tools (F1).** `FleetTools` turns each worker's AgentCard into a Spring
  AI tool (`fleet_summarizer_Brief`, `fleet_translator_TranslateTask`); the
  orchestrator's `ChatClient` sees the live set on every request.
- **Spring AI workers on the fleet.** Each worker's model call runs inside a
  take. `TakeLeaseAdvisor`, added to every auto-configured `ChatClient` by the
  library, renews the take lease on every model round-trip, so a two-minute lease
  covers any length of work while the worker makes progress.
- **Chat memory in the space (F3).** With `agentspaces.springai.memory.enabled`,
  each worker's conversation is stored in the replicated `conversations` space,
  keyed by the task's entry ID, so a task that reappears after a crash resumes
  with its conversation.

## The API in this example

The workers:

```java
@SpaceAgent(name = "summarizer", description = "Summarizes a topic into a short briefing")
public static class Summarizer {
    private final ChatClient chat;

    Summarizer(ChatClient.Builder builder) { this.chat = builder.build(); }

    @SpaceTake(space = "work", lease = "2m")
    public Summary summarize(Brief brief) {
        return new Summary(brief.topic(), chat.prompt().user("Summarize: " + brief.topic()).call().content());
    }
}
```

The orchestrator:

```java
Orchestrator(ChatClient.Builder builder, FleetTools fleet) {
    this.chat = builder.defaultToolCallbacks(fleet.toolCallbacks()).build();
}

public String ask(String question) {
    return chat.prompt().user(question).call().content();
}
```

Each application's configuration, as properties (in YAML under Spring Boot):

```yaml
agentspaces:
  security.profile: dev-local
  bind: 127.0.0.1:7901
  groups:
    - name: springai-fleet
      founding: springai-fleet-example-v1
      seeds: []                       # the other two list 127.0.0.1:7901
      spaces:
        - { name: work }
        - { name: conversations }
  springai:
    memory.enabled: true
```

| Type | From | Role here |
| --- | --- | --- |
| `@SpaceAgent`, `@SpaceTake` | `agentspaces-spring-boot-starter`, `agentspaces-agent` | A Spring bean that works tasks from the space |
| `ChatClient.Builder` | Spring AI | Injected into each agent; the library's customizers add the lease and memory advisors |
| `FleetTools` | `agentspaces-springai` | The fleet's AgentCards as Spring AI tools |
| `SpaceChatMemoryRepository` | `agentspaces-springai` | The workers' chat memory, in the space |

Source: [SpringAiFleet.java](src/main/java/ai/badmonkey/agentspaces/examples/springai/fleet/SpringAiFleet.java).

## Running it

From `agentspaces-springai/`, after installing the core libraries, install
the library and run the example (examples build only under `-Pexamples`):

```
mvn -q install -DskipTests
mvn -q -Pexamples -pl examples/springai-fleet exec:java
```

The demo starts all three applications in one process on ports 7901 to 7903,
waits for the workers' cards, and prints the fleet tools and the orchestrator's
answer. It runs on a scripted demo model (`DemoChatModel`), so it needs no API
key. To use a real model, add a Spring AI model starter (for example
`spring-ai-starter-model-openai`), configure its key, and set
`example.demo-model=false`.

[SpringAiFleetTest](src/test/java/ai/badmonkey/agentspaces/examples/springai/fleet/SpringAiFleetTest.java)
runs the three applications over TCP on free ports and asserts that the answer
carries both workers' results and that the workers' conversations are in the
space:

```
mvn -q -Pexamples -pl examples/springai-fleet test
```

## Next steps

- **Model access through the fleet (F4).** Enable
  `agentspaces.springai.model-client.enabled` on the orchestrator and
  `model-server.enabled` on a peer that holds the provider key: the orchestrator
  then needs no key, a model server that dies mid-call is replaced by another,
  and streaming works with `chat.prompt().stream()`.
- **Discovery by meaning (F2).** Enable `discovery-tools.agents` so the model can
  ask `find_fleet_agents` which tool to call when the fleet is large, and set
  `embedder.type=spring-ai` so discovery ranks with a real embedding model (F5).
- **Watch spend (F6).** Enable `usage.enabled` and the fleet console to see token
  use per model and per agent, fleet-wide.
- **Reach MCP clients (F7).** With Spring AI's MCP server starter, set
  `mcp.enabled` and `mcp.include-agents` to let Claude Desktop or an IDE agent
  call the fleet.
- **Patterns.** [springai-patterns](../springai-patterns/README.md) shows model
  judges voting with `@Ballot`, models priced by tokens under AUCTION, and an
  extraction ensemble.
