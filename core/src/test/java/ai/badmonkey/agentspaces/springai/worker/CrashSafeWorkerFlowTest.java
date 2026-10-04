/*
 * Copyright 2026 Bad Monkey, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.badmonkey.agentspaces.springai.worker;

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3.7, F3 end to end over TCP. Worker W1 takes a task under a two-second
 * lease and runs a tool loop far longer than that; the TakeLeaseAdvisor renews
 * on every round-trip, so W2, running beside it, never gets the task. Then W1 is
 * killed mid-loop: its lease lapses, the task reappears at W2, and W2's first
 * prompt already holds W1's conversation, read from the space by the task's
 * entry ID.
 */
class CrashSafeWorkerFlowTest {

    private static final String FOUNDING = "springai-crash-safe-v1";

    public record PlanTask(String topic) {
    }

    public record Plan(String topic, String outline, String detail, String by) {
    }

    record Step(int n) {
    }

    /** The worker: two model calls in one task, sharing the task's conversation. */
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

    /**
     * W1: outlines, then works a long tool loop; each step takes a second, so
     * Spring AI's default cap of 40 calls per tool leaves a 40-second loop,
     * far longer than the test runs it.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class FirstWorker {
        final AtomicInteger steps = new AtomicInteger();
        final AtomicBoolean killed = new AtomicBoolean();

        @Bean
        ChatModel chatModel() {
            return new ScriptedChatModel(prompt -> {
                if (killed.get()) {
                    // Spring AI hands a tool's exception back to the model as a result,
                    // so the kill surfaces here: a killed worker never finishes its task.
                    throw new IllegalStateException("W1 killed");
                }
                Message last = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
                if (last instanceof UserMessage user && user.getText().startsWith("outline")) {
                    return new AssistantMessage("outline: gather, compare, decide");
                }
                return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "s" + steps.get(), "function", "slow_step", "{\"n\":" + steps.get() + "}"))).build();
            });
        }

        @Bean
        ToolCallback slowStep() {
            return FunctionToolCallback.builder("slow_step", (Step step) -> {
                steps.incrementAndGet();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    killed.set(true);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("killed");
                }
                return "step " + step.n() + " done";
            }).description("one slow step").inputType(Step.class).build();
        }

        @Bean
        Planner planner(ChatClient.Builder builder, List<ToolCallback> tools) {
            return new Planner(builder, tools, "w1");
        }
    }

    /** W2: answers at once, and records every prompt it sees. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SecondWorker {
        @Bean
        ScriptedChatModel chatModel() {
            return new ScriptedChatModel(prompt -> {
                Message last = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
                return new AssistantMessage(last.getText().startsWith("outline")
                        ? "outline: resumed" : "detail: finished");
            });
        }

        @Bean
        Planner planner(ChatClient.Builder builder) {
            return new Planner(builder, List.of(), "w2");
        }
    }

    private static Map<String, Object> workerProperties() {
        return Map.of("agentspaces.springai.memory.enabled", "true",
                "agentspaces.springai.memory.conversation-id", "take");
    }

    @Test
    @Timeout(180)
    void aLongLoopKeepsItsClaimAndAKilledWorkersTaskResumesWithItsConversation() throws Exception {
        int seed = TestPeer.freePort();
        try (TestPeer desk = TestPeer.start(FOUNDING, "test-fleet", seed, 0, "tasks", "conversations")) {
            ConfigurableApplicationContext first = new SpringApplicationBuilder(FirstWorker.class)
                    .properties(FleetApps.nodeProperties(FOUNDING, seed, "tasks", "conversations"))
                    .properties(workerProperties()).run();
            ConfigurableApplicationContext second = null;
            try {
                FirstWorker w1 = first.getBean(FirstWorker.class);
                Thread.sleep(1500); // membership settles
                Space tasks = desk.spaces.get("tasks");
                tasks.write(new PlanTask("the offsite"), Lease.of(Duration.ofMinutes(10)));
                await(() -> w1.steps.get() >= 1, Duration.ofSeconds(30));

                second = new SpringApplicationBuilder(SecondWorker.class)
                        .properties(FleetApps.nodeProperties(FOUNDING, seed, "tasks", "conversations"))
                        .properties(workerProperties()).run();
                ScriptedChatModel w2 = second.getBean(ScriptedChatModel.class);

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
                assertThat(w2.prompts.get(0).getInstructions())
                        .noneMatch(ToolResponseMessage.class::isInstance);
            } finally {
                if (second != null) {
                    second.close();
                }
                if (first != null) {
                    first.close();
                }
            }
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(100);
        }
    }
}
