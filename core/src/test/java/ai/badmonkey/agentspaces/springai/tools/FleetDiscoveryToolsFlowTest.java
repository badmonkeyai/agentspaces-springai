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

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.connect.ConnectorRuntime;
import ai.badmonkey.agentspaces.connect.TableAssetProvider;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2 over TCP: a model finds an agent by meaning with find_fleet_agents, then
 * calls the fleet tool the result names; and fetch_fleet_data reads an asset a
 * connector peer serves, executing against the source once for two questions.
 */
class FleetDiscoveryToolsFlowTest {

    private static final String FOUNDING = "springai-discovery-flow-v1";

    public record Brief(String topic) {
    }

    public record Summary(String topic, String text) {
    }

    @AgentSpec(name = "summarizer", description = "Summarizes briefs about any topic",
            goals = {"summarize briefs"})
    public static class Summarizer {
        @SpaceTake(space = "work", pollTimeout = "PT0.2S")
        public Summary summarize(Brief brief) {
            return new Summary(brief.topic(), "summary of " + brief.topic());
        }
    }

    /** Round 1 asks the fleet; round 2 calls the tool the answer named; round 3 reports. */
    static ScriptedChatModel findThenCall() {
        Pattern toolName = Pattern.compile("\"tools\":\\[\"([^\"]+)\"");
        return new ScriptedChatModel(prompt -> {
            Message last = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
            if (last instanceof ToolResponseMessage responses) {
                ToolResponseMessage.ToolResponse response = responses.getResponses().get(0);
                if (response.name().equals("find_fleet_agents")) {
                    Matcher found = toolName.matcher(response.responseData());
                    if (found.find()) {
                        return AssistantMessage.builder().content("").toolCalls(List.of(
                                new AssistantMessage.ToolCall("c2", "function", found.group(1),
                                        "{\"topic\":\"gossip\"}"))).build();
                    }
                    return new AssistantMessage("no agent found: " + response.responseData());
                }
                return new AssistantMessage("done: " + response.responseData());
            }
            return AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("c1", "function", "find_fleet_agents",
                            "{\"question\":\"summarizes briefs about a topic\",\"limit\":3}"))).build();
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        ScriptedChatModel scriptedChatModel() {
            return findThenCall();
        }

        @Bean
        ChatClient chat(ScriptedChatModel model, FleetTools fleet, FleetDiscoveryTools discovery) {
            return ChatClient.builder(model)
                    .defaultToolCallbacks(fleet.toolCallbacks(), discovery.toolCallbacks()).build();
        }
    }

    @Test
    @Timeout(120)
    void theModelFindsAnAgentByMeaningThenCallsItAndDataIsFetchedOnce() throws Exception {
        int seed = TestPeer.freePort();
        TableAssetProvider orders = new TableAssetProvider("orders", "postgres://ops/public.orders",
                "Customer order history with settlement status", "com.example.OrderRow#v1",
                Duration.ofMinutes(5), List.of(Map.of("id", "1", "region", "eu"),
                Map.of("id", "2", "region", "us"), Map.of("id", "3", "region", "eu")));
        try (TestPeer worker = TestPeer.start(FOUNDING, "test-fleet", seed, 0, "work", "data");
             ConnectorRuntime connector = new ConnectorRuntime(worker.spaces.get("data"),
                     worker.discovery, worker.identity, "pg-connector", orders, worker.runtime.id(),
                     InstantSource.system(), Duration.ofMinutes(1));
             ConfigurableApplicationContext app = new SpringApplicationBuilder(App.class)
                     .properties(FleetApps.nodeProperties(FOUNDING, seed, "work", "data"))
                     .properties(Map.of(
                             "agentspaces.springai.discovery-tools.agents", "true",
                             "agentspaces.springai.discovery-tools.assets", "true",
                             "agentspaces.springai.discovery-tools.data", "true",
                             "agentspaces.springai.discovery-tools.timeout", "1s"))
                     .run()) {
            worker.binder.bind(new Summarizer());
            connector.start();
            FleetTools fleet = app.getBean(FleetTools.class);
            await(() -> fleet.current().stream()
                    .anyMatch(t -> t.getToolDefinition().name().equals("fleet_summarizer_Brief")),
                    Duration.ofSeconds(60));

            String answer = app.getBean(ChatClient.class).prompt().user("summarize gossip").call().content();
            assertThat(answer).startsWith("done: ").contains("summary of gossip");

            ToolCallback[] discovery = app.getBean(FleetDiscoveryTools.class).toolCallbacks().getToolCallbacks();
            ToolCallback list = byName(discovery, "list_fleet_assets");
            ToolCallback fetch = byName(discovery, "fetch_fleet_data");
            assertThat(list.call("{\"question\":\"customer order history\",\"limit\":3}"))
                    .contains("\"asset\":\"orders\"");
            assertThat(fetch.call("{\"asset\":\"orders\",\"parameters\":{\"region\":\"eu\"}}"))
                    .contains("\"fromCache\":false", "\"region\":\"eu\"");
            await(() -> fetch.call("{\"asset\":\"orders\",\"parameters\":{\"region\":\"eu\"}}")
                    .contains("\"fromCache\":true"), Duration.ofSeconds(20));
            assertThat(orders.executions()).isEqualTo(1);
        }
    }

    private static ToolCallback byName(ToolCallback[] tools, String name) {
        return Arrays.stream(tools).filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
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
