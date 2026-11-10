# How to use AI with AgentSpaces

Written for: Spring AI developers who build with agentspaces-springai.

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

Source: [`examples/springai-fleet/src/main/java/ai/badmonkey/agentspaces/examples/springai/fleet/SpringAiFleet.java`](../examples/springai-fleet/src/main/java/ai/badmonkey/agentspaces/examples/springai/fleet/SpringAiFleet.java)
and [`SpringAiFleetTest.theOrchestratorsModelCallsWorkersOnOtherPeersAsTools`](../examples/springai-fleet/src/test/java/ai/badmonkey/agentspaces/examples/springai/fleet/SpringAiFleetTest.java);
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

Source: [`core/src/test/java/ai/badmonkey/agentspaces/springai/worker/CrashSafeWorkerFlowTest.java`](../core/src/test/java/ai/badmonkey/agentspaces/springai/worker/CrashSafeWorkerFlowTest.java),
test `aLongLoopKeepsItsClaimAndAKilledWorkersTaskResumesWithItsConversation`;
run with `mvn -q -o test -pl core -Dtest=CrashSafeWorkerFlowTest -Dsurefire.failIfNoSpecifiedTests=false`.
Memory is a checkpoint, so a tool the model asks for again runs again; make
tool side effects idempotent. The related allocation pattern, two model
workers whose `@BidFunction` prices each task by tokens so routine work goes to
the cheap model and hard work to the premium one under the AUCTION strategy,
is [`TokenPricedAuction.java`](../examples/springai-patterns/src/main/java/ai/badmonkey/agentspaces/examples/springai/patterns/TokenPricedAuction.java)
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
[`core/src/test/java/ai/badmonkey/agentspaces/springai/model/FleetModelSpringBootTest.java`](../core/src/test/java/ai/badmonkey/agentspaces/springai/model/FleetModelSpringBootTest.java),
tests `theFleetModelInjectedByNameIsServedByTheOtherApplication` and
`theFleetModelStreamsFromTheOtherApplication`; run with
`mvn -q -o test -pl core -Dtest=FleetModelSpringBootTest -Dsurefire.failIfNoSpecifiedTests=false`.
A dead server's request is taken by another server with the next attempt
number, routing is by model name or by price under AUCTION, and a caller
accepts a response only from an issuer the authorizer permits `MODEL_SERVE`
for the model.
