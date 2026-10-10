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
package ai.badmonkey.agentspaces.springai.tools;

import ai.badmonkey.agentspaces.agent.capability.VoteClient;
import ai.badmonkey.agentspaces.springai.FleetContext;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F9 over TCP, the Spring AI counterpart of the LangChain4j layer-4 tools
 * test: two Spring applications in one group, each with the capability tools
 * on. A model on one opens a vote and casts; the other casts; the decision and
 * tally read the same everywhere, and a scripted {@code ChatClient} reads the
 * decision through the tool loop. Both then contribute to one push-sum epoch,
 * and the estimate settles at the fleet average.
 */
class FleetCapabilityToolsFlowTest {

    private static final String FOUNDING = "springai-capability-flow-v1";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        ScriptedChatModel scriptedChatModel() {
            return ScriptedChatModel.callingTools(List.of(
                    new AssistantMessage.ToolCall("c1", "function", "read_decision", "{\"id\":\"plan\"}")));
        }

        @Bean
        ChatClient chat(ScriptedChatModel model, FleetCapabilityTools capability) {
            return ChatClient.builder(model).defaultToolCallbacks(capability.toolCallbacks()).build();
        }
    }

    private static ConfigurableApplicationContext peer(int bindPort, int seed) {
        return new SpringApplicationBuilder(App.class)
                .properties(FleetApps.nodeProperties(bindPort, FOUNDING, seed, "work", "votes"))
                .properties(Map.of("agentspaces.springai.capability-tools.vote", "true",
                        "agentspaces.springai.capability-tools.aggregate", "true",
                        "agentspaces.springai.capability-tools.settle-timeout", "15s"))
                .run();
    }

    @Test
    @Timeout(120)
    void theCapabilityToolsLetAModelVoteAndContribute() throws Exception {
        int seed = TestPeer.freePort();
        try (ConfigurableApplicationContext a = peer(seed, 0);
             ConfigurableApplicationContext b = peer(TestPeer.freePort(), seed)) {
            Thread.sleep(1500);
            FleetCapabilityTools toolsA = a.getBean(FleetCapabilityTools.class);
            FleetCapabilityTools toolsB = b.getBean(FleetCapabilityTools.class);
            assertThat(toolsA.toolCallbacks().getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                    .containsExactlyInAnyOrder("propose_vote", "cast_ballot", "read_tally", "read_decision",
                            "contribute", "read_estimate");

            assertThat(toolsA.proposeVote("plan", "Adopt the plan?", List.of("yes", "no"), 2))
                    .contains("\"quorum\":2", "\"proposalId\":\"plan\"");
            assertThat(toolsA.proposeVote("plan", "Adopt the plan?", List.of("yes", "no"), 2))
                    .as("idempotent on the id").contains("\"quorum\":2");
            assertThat(toolsA.castBallot("plan", "yes")).contains("\"cast\":\"yes\"");
            assertThat(toolsA.castBallot("plan", "maybe")).contains("error");
            assertThat(toolsA.readDecision("plan")).contains("\"open\":true");
            await(() -> b.getBean(FleetContext.class).group().capability(VoteClient.class)
                    .proposal("plan").isPresent(), Duration.ofSeconds(30));
            assertThat(toolsB.castBallot("plan", "yes")).contains("\"cast\":\"yes\"");
            await(() -> toolsA.readDecision("plan").contains("winner"), Duration.ofSeconds(30));
            assertThat(toolsA.readDecision("plan")).contains("\"winner\":\"yes\"");
            assertThat(toolsA.readTally("plan")).contains("\"yes\":2");
            await(() -> toolsB.readDecision("plan").contains("\"winner\":\"yes\""), Duration.ofSeconds(30));

            String answer = a.getBean(ChatClient.class).prompt().user("What was decided?").call().content();
            assertThat(answer).startsWith("results: read_decision=").contains("\"winner\":\"yes\"");

            assertThat(toolsA.contribute("load", 10.0)).contains("\"contributed\":10.0");
            assertThat(toolsB.contribute("load", 30.0)).contains("\"contributed\":30.0");
            // The settle rule can fire on an early plateau; the estimate keeps converging on
            // every tick, so wait for it to land at the fleet average rather than read it once.
            await(() -> estimate(toolsA.readEstimate("load")) >= 19.0 && estimate(toolsA.readEstimate("load")) <= 21.0,
                    Duration.ofSeconds(40));
            String estimate = toolsA.readEstimate("load");
            assertThat(estimate).contains("\"settled\":");
            assertThat(estimate(estimate)).isBetween(19.0, 21.0);
            assertThat(toolsA.readEstimate("nothing")).contains("\"known\":false");
        }
    }

    private static double estimate(String json) {
        return json.contains("\"estimate\"")
                ? Double.parseDouble(json.replaceAll(".*\"estimate\":([0-9.]+).*", "$1")) : Double.NaN;
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(200);
        }
    }
}
