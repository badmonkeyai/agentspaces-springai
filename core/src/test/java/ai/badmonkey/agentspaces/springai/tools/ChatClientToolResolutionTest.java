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

import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.OnTimeout;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1.2, against the real Spring AI 2.0.1 ChatClient: a ToolCallbackProvider
 * attached once is consulted on every request, so the fleet's live tool set
 * reaches the model; and the model's tool call runs through the provider's
 * callback inside ChatClient's own tool loop.
 */
class ChatClientToolResolutionTest {

    @Test
    void theProviderIsResolvedPerRequestAndItsToolsRunInTheToolLoop() {
        List<FleetAction> fleet = new CopyOnWriteArrayList<>();
        FleetToolCallbackProvider provider = new FleetToolCallbackProvider(() -> List.copyOf(fleet),
                action -> true, ToolNamingStrategy.prefixed("fleet_"), new JsonMapper(),
                Duration.ofSeconds(5), OnTimeout.RESULT, ObservationRegistry.NOOP, Duration.ZERO,
                event -> { }, Clock.systemUTC());
        ScriptedChatModel model = ScriptedChatModel.callingTools(List.of(new AssistantMessage.ToolCall(
                "call-1", "function", "fleet_summarizer_Brief", "{\"topic\":\"leases\",\"words\":10}")));
        ChatClient chat = ChatClient.builder(model).defaultToolCallbacks(provider).build();

        fleet.add(FakeAction.summarizer());
        String answer = chat.prompt().user("summarize leases").call().content();

        assertThat(ScriptedChatModel.toolNames(model.prompts.get(0)))
                .containsExactly("fleet_summarizer_Brief");
        assertThat(answer).contains("fleet_summarizer_Brief=", "summary of leases");

        // The same client, a changed fleet: the next request sees the new tool set.
        fleet.add(new FakeAction("reviewer", false, in -> java.util.Optional.empty()));
        chat.prompt().user("summarize leases again").call().content();
        assertThat(ScriptedChatModel.toolNames(model.prompts.get(model.prompts.size() - 1)))
                .containsExactlyInAnyOrder("fleet_summarizer_Brief", "fleet_reviewer_Brief");
    }
}
