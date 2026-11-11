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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model service in front of Spring AI's real OpenAI client, blocking and streamed. A local stand-in for the
 * chat completions API answers in place of the hosted service, so the test needs no key and runs in every build.
 */
class OpenAiClientModelServiceTest {

    private static final String MODEL = "gpt-4.1-mini";
    private static final String KEY = "test-key-not-a-secret";

    @Test
    @Timeout(120)
    void theOpenAiClientAnswersThroughTheFleetBlockingAndStreamed() throws IOException {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        api.createContext("/v1/chat/completions", exchange -> answer(exchange, requests));
        api.start();
        OpenAiChatModel provider = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().baseUrl("http://127.0.0.1:" + api.getAddress().getPort() + "/v1")
                        .apiKey(KEY).model(MODEL).temperature(0.0).maxRetries(0).build())
                .build();
        try (LocalSpace space = LocalSpace.builder("model-requests", PeerIdentity.generate().agent("host")).build();
             SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler()) {
            ModelServer server = new ModelServer(space,
                    ModelCatalog.of(List.of(MODEL), Map.of("openai", provider), Map.of()),
                    ModelRequestRouter.byModel(), StreamChunkingPolicy.of(Duration.ofMillis(100), 32), scheduler,
                    "peer:openai-server", new ModelServer.Settings(Duration.ofMinutes(2), Duration.ofMinutes(2),
                            Duration.ofMinutes(10), 2));
            server.start();
            try {
                ChatClient chat = ChatClient.create(new FleetChatModel(space, (peer, op, scope) -> true,
                        "peer:openai-caller", MODEL, Duration.ofSeconds(90), StreamRestartPolicy.of(RestartMode.FAIL),
                        scheduler, ObservationRegistry.NOOP));
                String answer = chat.prompt().user("Reply with the single word: fleet").call().content();
                assertThat(answer).isEqualTo("fleet");
                String streamed = String.join("", chat.prompt().user("Count from one to five in words.")
                        .stream().content().collectList().block(Duration.ofSeconds(90)));
                assertThat(streamed).isEqualTo("one two three four five");
            } finally {
                server.stop();
            }
        } finally {
            api.stop(0);
        }
        assertThat(requests).hasSize(2).allSatisfy(r -> assertThat(r)
                .contains("Bearer " + KEY).contains("\"model\":\"" + MODEL + "\""));
        assertThat(requests.get(0)).contains("Reply with the single word: fleet").doesNotContain("\"stream\":true");
        assertThat(requests.get(1)).contains("Count from one to five in words.").contains("\"stream\":true");
    }

    /** The chat completions API's two answers: one JSON body, or server-sent chunks ending in {@code [DONE]}. */
    private static void answer(HttpExchange exchange, List<String> requests) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
        boolean stream = body.contains("\"stream\":true");
        String reply;
        if (stream) {
            StringBuilder sse = new StringBuilder();
            List<String> words = List.of("one", " two", " three", " four", " five");
            for (int i = 0; i < words.size(); i++) {
                String finish = i == words.size() - 1 ? "\"stop\"" : "null";
                sse.append("data: {\"id\":\"c-2\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"")
                        .append(MODEL).append("\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"")
                        .append(words.get(i)).append("\"},\"logprobs\":null,\"finish_reason\":").append(finish)
                        .append("}]}\n\n");
            }
            reply = sse.append("data: [DONE]\n\n").toString();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        } else {
            reply = "{\"id\":\"c-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"" + MODEL + "\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"fleet\",\"refusal\":null},"
                    + "\"logprobs\":null,\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":1,\"total_tokens\":10}}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
