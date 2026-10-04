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
package ai.badmonkey.agentspaces.springai.model;

import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.space.local.LocalSpace;
import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.RestartMode;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model service against a real provider, opt-in: runs only with
 * {@code AGENTSPACES_LIVE_MODELS=1} and {@code OPENAI_API_KEY} set, and never
 * in the default build. A real OpenAI model is served by a ModelServer and
 * called through FleetChatModel, blocking and streamed.
 */
@EnabledIfEnvironmentVariable(named = "AGENTSPACES_LIVE_MODELS", matches = "1")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class LiveModelServiceTest {

    @Test
    @Timeout(120)
    void aRealModelAnswersThroughTheFleetBlockingAndStreamed() {
        String model = System.getenv().getOrDefault("AGENTSPACES_LIVE_MODEL", "gpt-4.1-mini");
        OpenAiChatModel provider = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().apiKey(System.getenv("OPENAI_API_KEY"))
                        .model(model).temperature(0.0).build())
                .build();
        try (LocalSpace space = LocalSpace.builder("model-requests", PeerIdentity.generate().agent("host")).build();
             SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler()) {
            ModelServer server = new ModelServer(space,
                    ModelCatalog.of(List.of(model), Map.of("openai", provider), Map.of()),
                    ModelRequestRouter.byModel(), StreamChunkingPolicy.of(Duration.ofMillis(100), 32), scheduler,
                    "peer:live-server", new ModelServer.Settings(Duration.ofMinutes(2), Duration.ofMinutes(2),
                            Duration.ofMinutes(10), 2));
            server.start();
            try {
                ChatClient chat = ChatClient.create(new FleetChatModel(space, (peer, op, scope) -> true,
                        "peer:live-caller", model, Duration.ofSeconds(90), StreamRestartPolicy.of(RestartMode.FAIL),
                        scheduler, ObservationRegistry.NOOP));
                String answer = chat.prompt().user("Reply with the single word: fleet").call().content();
                assertThat(answer).containsIgnoringCase("fleet");
                String streamed = String.join("", chat.prompt().user("Count from one to five in words.")
                        .stream().content().collectList().block(Duration.ofSeconds(90)));
                assertThat(streamed.toLowerCase()).contains("one").contains("five");
            } finally {
                server.stop();
            }
        }
    }
}
