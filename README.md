# agentspaces-springai

## Introducing AgentSpaces by Bad Monkey AI

**A peer-to-peer coordination fabric for agents, services, and the people who work with them.**

AgentSpaces is a P2P framework to quickly discover and join P2P networks, where agents, software services, and people share assets, queries, results, and coordinate. The framework helps you build fleets of agents, tie in other software, and coordinate across them with a replicated tuple space, quorum building, and more. Agents post work, claim tasks, publish results, vote on decisions, and recover from each other's failures. There is no broker, no registry, no scheduler, and no server to keep alive. Coordination is peer-to-peer, secured end to end, and replicated across the fleet.

## Quick start: build your first agent with Spring AI

An agent is a method. Its parameter is the work it takes, and its return value is the next entry in the space. With Spring AI the method sits on a bean and calls a model: the space hands it one `Order` at a time under a lease, your `ChatClient` writes the packing note, and the returned `Shipment` completes the order.

**1. Add the dependencies.** The library, the AgentSpaces starter, and a Spring AI model starter. The Maven BOM and the Gradle forms are under [Getting started](#getting-started).

```xml
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-springai</artifactId>
  <version>0.4.0</version>
</dependency>
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-spring-boot-starter</artifactId>
  <version>0.4.0</version>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

**2. Configure the peer.** One YAML file builds it. The default `mtls` security profile secures every link, so the file has no security block. The `${shop.*}` placeholders let you start a second instance from the command line.

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
  main:
    web-application-type: none   # the fleet is the interface
    keep-alive: true             # nothing else holds the JVM open

agentspaces:
  bind: 127.0.0.1:${shop.port:7701}
  groups:
    - name: shop
      founding: shop-v1
      seeds: ${shop.seeds:}      # empty on the first instance
      spaces:
        - name: orders
```

**3. Write the agent.** Two records name the entries, `@SpaceAgent` turns the bean into an agent, and `@SpaceTake` says what it takes and how long it may hold it. The `ApplicationRunner` only feeds the fleet an order.

```java
public record Order(String orderId, String item) {
}

public record Shipment(String orderId, String item, String note) {
}
```

```java
@SpaceAgent(name = "fulfiller", description = "Ships orders and writes a packing note")
public class Fulfiller {
    private final ChatClient chat;

    Fulfiller(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    @SpaceTake(lease = "2m")
    public Shipment ship(Order order) {
        String note = chat.prompt().user("Write a one-line packing note for: " + order.item()).call().content();
        return new Shipment(order.orderId(), order.item(), note);
    }
}
```

```java
@SpringBootApplication
public class ShopApp {

    public static void main(String[] args) {
        SpringApplication.run(ShopApp.class, args);
    }

    @Bean
    ApplicationRunner desk(AgentSpaces spaces, @Value("${shop.order:}") String item) {
        return args -> {
            if (!item.isBlank()) {
                Space orders = spaces.group("shop").space("orders");
                orders.write(new Order("o-1", item), Lease.of(Duration.ofMinutes(10)));
                orders.read(Template.of(Shipment.class), Duration.ofSeconds(30)).ifPresent(System.out::println);
            }
        };
    }
}
```

**4. Run two instances.** Set `OPENAI_API_KEY` (any Spring AI model starter works), then start the first instance, and a second one that joins through it and writes an order. The listings omit imports.

```
mvn -q package
java -jar target/<your-app>.jar
java -jar target/<your-app>.jar --shop.port=7702 --shop.seeds=127.0.0.1:7701 --shop.order=kite
```

The second instance prints the shipment with its packing note. Either instance can take the order, because both run the same agent.

**5. Kill one.** Add `System.out.println("shipping " + order.orderId());` and `Thread.sleep(10_000);` at the top of `ship` (the method then needs `throws InterruptedException`), and shorten the lease to `@SpaceTake(lease = "30s")`. Start both instances, write an order from the second, and run `kill -9` on whichever instance prints `shipping`. If you killed the first instance, the second prints the shipment. If you killed the second, the first prints `shipping o-1` again when the lease lapses. The `TakeLeaseAdvisor` renews the lease on every model round trip while an instance lives, so only a dead instance loses its order.

The sections below add the rest: fleet tools for an orchestrator's model, crash-safe model loops with chat memory, model access as a fleet service, and MCP export.

## About agentspaces-springai

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

## How to use AI with AgentSpaces

At the end of this section you will have a Spring AI worker that takes work
from a shared space and answers it with its model, an orchestrator whose
`ChatClient` calls that worker on another machine as a tool, a worker whose
lease stays alive for as long as its model loop makes progress and whose
task resumes on another peer, with its conversation, when it is killed, and
a caller that reaches a model through the fleet while the provider key stays
on the serving peer. Every snippet is cut from checked-in code that runs
under the build gate; the file and the test are named beside each one, and
imports and Javadoc are elided.

**Build an agent, and call it as a tool from another peer.** The worker is
ordinary Spring AI code: a `@SpaceAgent` bean with an injected
`ChatClient.Builder` and one `@SpaceTake` method. The orchestrator attaches
`FleetTools` to its own `ChatClient`; each worker's AgentCard becomes a
`ToolCallback` named `fleet_<agent>_<InputType>`, a call writes a leased task
into the space, and the result comes back as the tool's answer. No
application knows another's address.

```java
@SpaceAgent(name = "summarizer", description = "Summarizes a topic into a short briefing",
        goals = {"summarize topics"})
public static class Summarizer {
    private final ChatClient chat;

    Summarizer(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    @SpaceTake(space = "work", lease = "2m")
    public Summary summarize(Brief brief) {
        return new Summary(brief.topic(), chat.prompt().user("Summarize: " + brief.topic()).call().content());
    }
}

public static class Orchestrator {
    private final ChatClient chat;

    Orchestrator(ChatClient.Builder builder, FleetTools fleet) {
        this.chat = builder.defaultToolCallbacks(fleet.toolCallbacks()).build();
    }

    public String ask(String question) {
        return chat.prompt().user(question).call().content();
    }
}
```

The test starts the summarizer, a translator, and the orchestrator as three
Spring Boot applications on free TCP ports, waits for the workers' cards, and
asks one question; the scripted model calls both fleet tools and writes the
briefing from their results:

```java
SpringAiFleet.awaitTools(orchestrator, Duration.ofSeconds(60));
String answer = orchestrator.getBean(SpringAiFleet.Orchestrator.class)
        .ask("Brief me on tuple spaces, in French too");
assertThat(answer).startsWith("Here is your briefing.")
        .contains("tuple spaces coordinate agents through a shared, leased space.")
        .contains("[fr] tuple spaces");
```

Source: [`examples/springai-fleet/src/main/java/ai/badmonkey/agentspaces/examples/springai/fleet/SpringAiFleet.java`](examples/springai-fleet/src/main/java/ai/badmonkey/agentspaces/examples/springai/fleet/SpringAiFleet.java)
and [`SpringAiFleetTest.theOrchestratorsModelCallsWorkersOnOtherPeersAsTools`](examples/springai-fleet/src/test/java/ai/badmonkey/agentspaces/examples/springai/fleet/SpringAiFleetTest.java);
run the three applications in one process with
`mvn -q -Pexamples -pl examples/springai-fleet exec:java` (ports 7901 to 7903,
scripted model, no key), or the test with
`mvn -q -Pexamples -pl examples/springai-fleet test`. Set
`example.demo-model=false` and add a Spring AI model starter to use a real
provider; the agent code does not change.

**Survive a crash mid-model-loop.** `TakeLeaseAdvisor` is on by default and
joins every auto-configured `ChatClient.Builder`: it renews the in-flight take
on each model round-trip and on an interval while streaming, so progress
renews the lease and the lease can be two seconds. With chat memory in a space
(`agentspaces.springai.memory.enabled=true`, `conversation-id=take`), the
conversation is keyed by the task, so the peer that picks the task up after a
crash resumes where the dead one stopped.

```java
@AgentSpec(name = "planner", description = "Plans topics", goals = {"plan"})
public static class Planner {
    private final ChatClient chat;
    private final String name;

    Planner(ChatClient.Builder builder, List<ToolCallback> tools, String name) {
        this.chat = builder.defaultToolCallbacks(tools).build();
        this.name = name;
    }

    @SpaceTake(space = "tasks", lease = "2s", pollTimeout = "PT0.2S")
    public Plan plan(PlanTask task) {
        String outline = chat.prompt().user("outline " + task.topic()).call().content();
        String detail = chat.prompt().user("detail the outline").call().content();
        return new Plan(task.topic(), outline, detail, name);
    }
}
```

The test runs two Boot applications with this bean and a desk peer that
writes one `PlanTask`. The first worker's scripted model loops over a slow
tool; the second worker's model is idle:

```java
// Four lease periods of steady progress: W1 renews, W2 never gets the task.
int before = w1.steps.get();
Thread.sleep(8000);
assertThat(w1.steps.get() - before).as("W1 kept working").isGreaterThanOrEqualTo(5);
assertThat(w2.prompts).as("W2 never saw the task while W1 renewed").isEmpty();
// Kill W1 mid-loop. Its lease lapses and the task reappears at W2.
first.close();
first = null;
Optional<Plan> plan = tasks.read(Template.of(Plan.class), Duration.ofSeconds(60));
assertThat(plan).hasValueSatisfying(p -> {
    assertThat(p.by()).isEqualTo("w2");
    assertThat(p.detail()).isEqualTo("detail: finished");
});
// W2's very first prompt already held W1's conversation.
List<String> firstPrompt = w2.prompts.get(0).getInstructions().stream()
        .map(Message::getText).toList();
assertThat(firstPrompt).contains("outline the offsite", "outline: gather, compare, decide",
        "detail the outline");
```

Source: [`core/src/test/java/ai/badmonkey/agentspaces/springai/worker/CrashSafeWorkerFlowTest.java`](core/src/test/java/ai/badmonkey/agentspaces/springai/worker/CrashSafeWorkerFlowTest.java),
test `aLongLoopKeepsItsClaimAndAKilledWorkersTaskResumesWithItsConversation`;
run with `mvn -q -o test -pl core -Dtest=CrashSafeWorkerFlowTest -Dsurefire.failIfNoSpecifiedTests=false`.
Memory is a checkpoint, so a tool the model asks for again runs again; make
tool side effects idempotent. The related allocation pattern, two model
workers whose `@BidFunction` prices each task by tokens so routine work goes to
the cheap model and hard work to the premium one under the AUCTION strategy,
is [`TokenPricedAuction.java`](examples/springai-patterns/src/main/java/ai/badmonkey/agentspaces/examples/springai/patterns/TokenPricedAuction.java)
with `PatternsTest.tokenPricesRouteRoutineWorkToTheCheapModelAndHardWorkToThePremiumOne`
(`mvn -q -Pexamples -pl examples/springai-patterns test`).

**Share a model across peers, with the key on the server only.** `ModelServer`
on the peer that holds the provider credential takes `ModelRequest` entries
from the `model-requests` space and answers them with its `ChatModel`;
`FleetChatModel` on any other peer is a `ChatModel` whose provider is the
fleet. The caller keeps its own provider as the `ChatModel` by type and
reaches the fleet model by name:

```java
@SpringBootConfiguration
@EnableAutoConfiguration
static class ServerApp {
    @Bean
    ScriptedChatModel servedModel() {
        return ScriptedChatModel.answering("answer from the fleet")
                .streaming(ScriptedChatModel.chunks(Duration.ofMillis(20), "streamed ", "from ", "the fleet"));
    }
}

@SpringBootConfiguration
@EnableAutoConfiguration
static class CallerApp {
    @Bean
    ScriptedChatModel localModel() {
        return ScriptedChatModel.answering("answer from the local provider");
    }

    @Bean
    ChatClient fleetChat(@Qualifier("fleetChatModel") ChatModel fleet) {
        return ChatClient.create(fleet);
    }
}

server = new SpringApplicationBuilder(ServerApp.class)
        .properties(node)
        .properties(Map.of("agentspaces.springai.model-server.enabled", "true",
                "agentspaces.springai.model-server.models", "test-model",
                "agentspaces.springai.model-server.chunk-interval", "20ms"))
        .run();

ChatResponse response = fleetChat.prompt().user("hello fleet").call().chatResponse();
assertThat(response.getResult().getOutput().getText()).isEqualTo("answer from the fleet");
assertThat((String) response.getMetadata().get(FleetChatModel.SERVED_BY_KEY))
        .isEqualTo(server.getBean(PeerIdentity.class).peerId().value());
assertThat(localModel.prompts).as("the local provider was not asked").hasSize(localPrompts);
```

The caller's side is two properties, `agentspaces.springai.model-client.enabled=true`
and `model-client.timeout=30s`; a third test in the same class streams
`"streamed from the fleet"` through the same client. Source:
[`core/src/test/java/ai/badmonkey/agentspaces/springai/model/FleetModelSpringBootTest.java`](core/src/test/java/ai/badmonkey/agentspaces/springai/model/FleetModelSpringBootTest.java),
tests `theFleetModelInjectedByNameIsServedByTheOtherApplication` and
`theFleetModelStreamsFromTheOtherApplication`; run with
`mvn -q -o test -pl core -Dtest=FleetModelSpringBootTest -Dsurefire.failIfNoSpecifiedTests=false`.
A dead server's request is taken by another server with the next attempt
number, routing is by model name or by price under AUCTION, and a caller
accepts a response only from an issuer the authorizer permits `MODEL_SERVE`
for the model.

## Getting started

The library is on Maven Central under the group `ai.badmonkey.agentspaces`,
version `0.4.0`; no repository configuration is needed. Add it beside the
AgentSpaces starter and a Spring AI model starter. The library releases from
this repository on its own cadence, so it carries its version explicitly; the
core's `agentspaces-dependencies` BOM manages the starter's.

Maven (`pom.xml`):

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>ai.badmonkey.agentspaces</groupId>
      <artifactId>agentspaces-dependencies</artifactId>
      <version>0.4.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>ai.badmonkey.agentspaces</groupId>
    <artifactId>agentspaces-springai</artifactId>
    <version>0.4.0</version>
  </dependency>
  <dependency>
    <groupId>ai.badmonkey.agentspaces</groupId>
    <artifactId>agentspaces-spring-boot-starter</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
  </dependency>
</dependencies>
```

Gradle Kotlin DSL (`build.gradle.kts`):

```kotlin
dependencies {
    implementation(platform("ai.badmonkey.agentspaces:agentspaces-dependencies:0.4.0"))
    implementation("ai.badmonkey.agentspaces:agentspaces-springai:0.4.0")
    implementation("ai.badmonkey.agentspaces:agentspaces-spring-boot-starter")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
}
```

Gradle Groovy DSL (`build.gradle`):

```groovy
dependencies {
    implementation platform('ai.badmonkey.agentspaces:agentspaces-dependencies:0.4.0')
    implementation 'ai.badmonkey.agentspaces:agentspaces-springai:0.4.0'
    implementation 'ai.badmonkey.agentspaces:agentspaces-spring-boot-starter'
    implementation 'org.springframework.ai:spring-ai-starter-model-openai'
}
```

The Spring AI model starter is whichever provider the application uses; its
version comes from the `spring-ai-bom` or the Spring Boot parent, as in any
Spring AI project.

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
| F9. Capability tools | `FleetCapabilityTools` | `propose_vote`, `cast_ballot`, `read_tally`, `read_decision`, `contribute`, and `read_estimate`: the model opens votes, casts ballots, and contributes to fleet-wide averages as this peer's agent, each family enabled separately. With `capability-tools.content` the model also publishes a file as a fleet artifact and fetches, reads, describes, and lists artifacts moved by the content swarm (SPEC v0.1.23 §8); a `ModelRequest` whose media exceeds `agentspaces.content.escalate-above` travels as an artifact on its own. |

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
bean in the context to outside MCP clients. `FleetTools`, `FleetDiscoveryTools`,
and `FleetCapabilityTools` are therefore plain beans that hand out their
providers through `toolCallbacks()`, which the application attaches to a
`ChatClient` explicitly. The fleet crosses into MCP only through F7, which
exposes nothing until `agentspaces.springai.mcp.include-agents` names the agents
it may, and `mcp.discovery-tools` and `mcp.capability-tools` say the F2 and F9
tools go too.

### The 0.3.0 annotations

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
[patterns example](examples/springai-patterns/README.md) opens its judges' votes
with a `Motion` return. `AnnotationPassthroughTest` in `core/src/test` boots two
Spring Boot applications and proves each of these annotations and return
conventions through `@SpaceAgent` beans, reading every outcome from a space.

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
fails at startup with the YAML that adds it. F9 resolves the starter's vote and
aggregate capabilities (`agentspaces.capabilities.vote` and `.aggregate`, both
on by default) when a tool first runs; a tool whose capability is off answers
the model with an error.

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
    capability-tools:
      vote: true                      # propose_vote, cast_ballot, read_tally, read_decision
      aggregate: false                # contribute, read_estimate
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
      capability-tools: false
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
| `capability-tools.vote`, `.aggregate`, `.content` | `false` | Enable the vote tools (`propose_vote`, `cast_ballot`, `read_tally`, `read_decision`), the aggregate tools (`contribute`, `read_estimate`), and the content swarm tools (`publish_content`, `fetch_content`, `read_content`, `describe_content`, `list_content`; the starter's `agentspaces.content.enabled` must build the swarm) |
| `capability-tools.ballot-lease`, `.settle-timeout`, `.fetch-timeout` | `1h`, `10s`, `10m` | The lease of proposals and ballots the tools write; how long `read_estimate` waits for the estimate to settle; how long `fetch_content` waits for an artifact |
| `take-lease.enabled`, `take-lease.stream-renew-interval` | `true`, `30s` | The lease advisor |
| `memory.enabled`, `.space`, `.ttl`, `.conversation-id`, `.max-messages` | `false`, `conversations`, `24h`, `take`, `20` | Chat memory in the space |
| `model-client.enabled`, `.space`, `.model`, `.timeout`, `.stream.on-restart` | `false`, `model-requests`, server default, `120s`, `fail` | The calling side of F4 |
| `model-server.enabled`, `.space`, `.models`, `.routing`, `.prices.<model>` | `false`, `model-requests`, none, `model`, `1.0` | The serving side of F4 |
| `model-server.lease`, `.chunk-interval`, `.chunk-max-deltas`, `.chunk-lease`, `.response-lease`, `.concurrency`, `.model-beans.<model>` | `2m`, `100ms`, `32`, `2m`, `10m`, `4`, none | Server tuning; `model-beans` maps a model to a `ChatModel` bean when several providers are present |
| `embedder.type`, `.model`, `.require-match`, `.cache-size` | `hashing`, class name, `true`, `4096` | F5 |
| `usage.enabled`, `.metrics-space`, `.entry-lease`, `.publish-interval` | `false`, none, `24h`, `30s` | F6 |
| `mcp.enabled`, `.include-agents`, `.discovery-tools`, `.capability-tools` | `false`, none, `false`, `false` | F7; the last two also export the enabled F2 and F9 tools |
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
- **A capability tool acts as this peer's agent.** A ballot `cast_ballot` writes
  counts as the application's own vote, so enable F9 only on peers whose model
  should hold that vote.
- **Credentials stay on server peers.** Under F4, provider keys live only on the
  `ModelServer` peers.
- **MCP clients are outside the fleet.** F7 exposes nothing until named.

## Building and testing

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
parent pom. See [CONTRIBUTING.md](CONTRIBUTING.md).

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
