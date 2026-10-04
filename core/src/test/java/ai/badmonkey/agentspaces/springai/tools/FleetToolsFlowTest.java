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
import ai.badmonkey.agentspaces.api.ad.AgentCard;
import ai.badmonkey.agentspaces.identity.AdvertisementSigner;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1.7, the whole of F1 over TCP: a Spring Boot application's model calls
 * a summarizer and a translator that run on two other peers, as tools, through
 * ChatClient's own tool loop; and a card that lapses takes its tool with it,
 * announced by a FleetToolsChangedEvent.
 */
class FleetToolsFlowTest {

    private static final String FOUNDING = "springai-tools-flow-v1";

    public record Brief(String topic) {
    }

    public record Summary(String topic, String text) {
    }

    public record TranslateTask(String text, String language) {
    }

    public record Translation(String language, String translated) {
    }

    @AgentSpec(name = "summarizer", description = "Summarizes briefs", goals = {"summarize"})
    public static class Summarizer {
        @SpaceTake(space = "work", pollTimeout = "PT0.2S")
        public Summary summarize(Brief brief) {
            return new Summary(brief.topic(), "summary of " + brief.topic());
        }
    }

    @AgentSpec(name = "translator", description = "Translates text", goals = {"translate"})
    public static class Translator {
        @SpaceTake(space = "work", pollTimeout = "PT0.2S")
        public Translation translate(TranslateTask task) {
            return new Translation(task.language(), "[" + task.language() + "] " + task.text());
        }
    }

    /** The orchestrator application: a ChatClient with the fleet's tools. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class OrchestratorApp {
        final List<FleetToolsChangedEvent> events = new CopyOnWriteArrayList<>();

        @Bean
        ScriptedChatModel scriptedChatModel() {
            return ScriptedChatModel.callingTools(List.of(
                    new AssistantMessage.ToolCall("c1", "function", "fleet_summarizer_Brief",
                            "{\"topic\":\"tuple spaces\"}"),
                    new AssistantMessage.ToolCall("c2", "function", "fleet_translator_TranslateTask",
                            "{\"text\":\"hello fleet\",\"language\":\"fr\"}")));
        }

        @Bean
        ChatClient orchestrator(ScriptedChatModel model, FleetTools fleet) {
            return ChatClient.builder(model).defaultToolCallbacks(fleet.toolCallbacks()).build();
        }

        @EventListener
        void onToolsChanged(FleetToolsChangedEvent event) {
            events.add(event);
        }
    }

    @Test
    @Timeout(120)
    void theModelCallsAgentsOnOtherPeersAndALapsedCardTakesItsToolAway() throws Exception {
        int seed = TestPeer.freePort();
        try (TestPeer summarizerPeer = TestPeer.start(FOUNDING, "test-fleet", seed, 0, "work");
             TestPeer translatorPeer = TestPeer.start(FOUNDING, "test-fleet", TestPeer.freePort(), seed, "work");
             ConfigurableApplicationContext app = new SpringApplicationBuilder(OrchestratorApp.class)
                     .properties(FleetApps.nodeProperties(FOUNDING, seed, "work"))
                     .properties(Map.of("agentspaces.springai.tools.refresh-interval", "500ms"))
                     .run()) {
            summarizerPeer.binder.bind(new Summarizer());
            translatorPeer.binder.bind(new Translator());
            FleetTools fleet = app.getBean(FleetTools.class);

            await(() -> names(fleet.current()).containsAll(
                    List.of("fleet_summarizer_Brief", "fleet_translator_TranslateTask")),
                    Duration.ofSeconds(60));

            String answer = app.getBean(ChatClient.class).prompt().user("brief me").call().content();
            assertThat(answer)
                    .contains("fleet_summarizer_Brief=", "summary of tuple spaces")
                    .contains("fleet_translator_TranslateTask=", "[fr] hello fleet");

            // A card with a three-second lease: its tool appears, then lapses away.
            AgentCard ephemeral = new AgentCard(
                    "aspace://" + summarizerPeer.runtime.id().value() + "/agent/ephemeral",
                    summarizerPeer.identity.peerId(), summarizerPeer.runtime.id(), Instant.now(),
                    Duration.ofSeconds(3), summarizerPeer.identity.agent("ephemeral"),
                    "Briefly available", List.of("vanish"),
                    List.of(Brief.class.getName() + "#v1"), List.of(Summary.class.getName() + "#v1"),
                    Map.of());
            summarizerPeer.discovery.publish(new AdvertisementSigner().sign(ephemeral,
                    summarizerPeer.identity));
            OrchestratorApp orchestrator = app.getBean(OrchestratorApp.class);
            await(() -> orchestrator.events.stream().anyMatch(e -> e.added().contains("fleet_ephemeral_Brief")),
                    Duration.ofSeconds(30));
            await(() -> orchestrator.events.stream().anyMatch(e -> e.removed().contains("fleet_ephemeral_Brief")),
                    Duration.ofSeconds(30));
            assertThat(names(fleet.refresh())).doesNotContain("fleet_ephemeral_Brief")
                    .contains("fleet_summarizer_Brief", "fleet_translator_TranslateTask");
        }
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
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
