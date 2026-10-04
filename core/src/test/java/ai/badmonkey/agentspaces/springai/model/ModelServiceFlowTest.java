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
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 over TCP, on one fleet: a caller with no model provider, whose
 * auto-configured ChatClient runs on the fleet's model service, and a model
 * server whose scripted provider answers by the prompt's last user text.
 */
class ModelServiceFlowTest {

    private static final String FOUNDING = "springai-model-service-v1";
    private static final ChatResponseMetadata USAGE = ChatResponseMetadata.builder()
            .usage(new DefaultUsage(5, 7, 12)).build();

    static TestPeer seed;
    static ConfigurableApplicationContext server;
    static ConfigurableApplicationContext caller;

    record Lookup(String q) {
    }

    /** The provider on the serving peer. */
    static ScriptedChatModel provider() {
        return new ScriptedChatModel(prompt -> {
            Message last = last(prompt);
            if (last instanceof ToolResponseMessage tool) {
                return new AssistantMessage("final: " + tool.getResponses().get(0).responseData());
            }
            if (last.getText().equals("tool please")) {
                return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "c1", "function", "local_lookup", "{\"q\":\"fleet\"}"))).build();
            }
            if (last instanceof UserMessage user && !user.getMedia().isEmpty()) {
                byte[] bytes = user.getMedia().get(0).getDataAsByteArray();
                return new AssistantMessage("media " + bytes.length + " first=" + bytes[0]);
            }
            return new AssistantMessage("plain answer");
        }) {
            @Override
            public ChatResponse call(Prompt prompt) {
                ChatResponse plain = super.call(prompt);
                return new ChatResponse(plain.getResults(), USAGE);
            }
        }.streaming(prompt -> {
            Message last = last(prompt);
            if (last instanceof ToolResponseMessage tool) {
                return Flux.just(response("done: " + tool.getResponses().get(0).responseData(), true));
            }
            if (last.getText().equals("long stream")) {
                return Flux.range(0, 400).delayElements(Duration.ofMillis(25))
                        .map(i -> response("x", false))
                        .doOnCancel(() -> SERVER_CANCELLED.set(true));
            }
            if (last.getText().equals("stream a tool")) {
                return Flux.just(response("thinking ", false),
                        new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                                .toolCalls(List.of(new AssistantMessage.ToolCall("s1", "function",
                                        "local_lookup", "{\"q\":\"streamed\"}"))).build())), USAGE));
            }
            return Flux.just("Hel", "lo ", "fle", "et").delayElements(Duration.ofMillis(30))
                    .map(text -> response(text, text.equals("et")));
        });
    }

    static final AtomicBoolean SERVER_CANCELLED = new AtomicBoolean();
    static final AtomicInteger LOCAL_TOOL_CALLS = new AtomicInteger();

    private static ChatResponse response(String text, boolean withUsage) {
        List<Generation> generation = List.of(new Generation(new AssistantMessage(text)));
        return withUsage ? new ChatResponse(generation, USAGE) : new ChatResponse(generation);
    }

    private static Message last(Prompt prompt) {
        return prompt.getInstructions().get(prompt.getInstructions().size() - 1);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ServerApp {
        @Bean
        ScriptedChatModel scriptedProvider() {
            return provider();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CallerApp {
    }

    @BeforeAll
    static void startFleet() throws Exception {
        int port = TestPeer.freePort();
        seed = TestPeer.start(FOUNDING, "test-fleet", port, 0, "model-requests");
        server = new SpringApplicationBuilder(ServerApp.class)
                .properties(FleetApps.nodeProperties(FOUNDING, port, "model-requests"))
                .properties(Map.of("agentspaces.springai.model-server.enabled", "true",
                        "agentspaces.springai.model-server.models", "test-model",
                        "agentspaces.springai.model-server.chunk-interval", "20ms"))
                .run();
        caller = new SpringApplicationBuilder(CallerApp.class)
                .properties(FleetApps.nodeProperties(FOUNDING, port, "model-requests"))
                .properties(Map.of("agentspaces.springai.model-client.enabled", "true",
                        "agentspaces.springai.model-client.timeout", "30s"))
                .run();
        Thread.sleep(1500); // membership settles
    }

    @AfterAll
    static void stopFleet() {
        caller.close();
        server.close();
        seed.close();
    }

    private static ChatClient chat() {
        return caller.getBean(ChatClient.Builder.class).build();
    }

    @Test
    @Timeout(60)
    void theCallersOnlyChatModelIsTheFleetAndAPlainCallRoundTrips() {
        assertThat(caller.getBean(ChatModel.class)).isInstanceOf(FleetChatModel.class);
        ChatResponse response = chat().prompt().user("hello").call().chatResponse();
        assertThat(response.getResult().getOutput().getText()).isEqualTo("plain answer");
        assertThat((String) response.getMetadata().get(FleetChatModel.SERVED_BY_KEY))
                .isEqualTo(server.getBean(PeerIdentity.class).peerId().value());
        assertThat((Integer) response.getMetadata().get(FleetChatModel.ATTEMPT_KEY)).isEqualTo(1);
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(12);
    }

    @Test
    @Timeout(60)
    void toolCallsComeBackAndRunOnTheCallerWithTheCallersTools() {
        int before = LOCAL_TOOL_CALLS.get();
        String answer = chat().prompt().user("tool please")
                .toolCallbacks(FunctionToolCallback.builder("local_lookup", (Lookup in) -> {
                    LOCAL_TOOL_CALLS.incrementAndGet();
                    return "found " + in.q();
                }).description("looks up locally").inputType(Lookup.class).build())
                .call().content();
        assertThat(answer).isEqualTo("final: \"found fleet\"");
        assertThat(LOCAL_TOOL_CALLS.get() - before).isEqualTo(1);
        ScriptedChatModel provider = server.getBean(ScriptedChatModel.class);
        Prompt toolRound = provider.prompts.stream()
                .filter(p -> last(p).getText().equals("tool please")).findFirst().orElseThrow();
        assertThat(ScriptedChatModel.toolNames(toolRound)).as("definitions traveled").containsExactly("local_lookup");
    }

    /**
     * A ReplicatedSpace refuses a payload over the 64 KiB inline limit unless it
     * travels content-addressed over the group's block exchange, so this request
     * reaching the server at all proves the CID path; the bytes arrive intact.
     */
    @Test
    @Timeout(60)
    void mediaLargerThanTheInlineLimitReachesTheServerIntact() {
        byte[] image = new byte[100 * 1024];
        image[0] = 7;
        String answer = chat().prompt()
                .user(u -> u.text("look").media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(image)))
                .call().content();
        assertThat(answer).isEqualTo("media 102400 first=7");
    }

    @Test
    @Timeout(60)
    void aStreamArrivesInOrderWithUsageOnTheLastResponse() {
        List<ChatResponse> responses = chat().prompt().user("stream please").stream().chatResponse()
                .collectList().block(Duration.ofSeconds(30));
        StringBuilder text = new StringBuilder();
        responses.forEach(r -> {
            if (r.getResult() != null && r.getResult().getOutput().getText() != null) {
                text.append(r.getResult().getOutput().getText());
            }
        });
        assertThat(text.toString()).isEqualTo("Hello fleet");
        assertThat(responses.get(responses.size() - 1).getMetadata().getUsage().getTotalTokens()).isEqualTo(12);
    }

    @Test
    @Timeout(60)
    void cancellingAStreamStopsTheServer() throws Exception {
        SERVER_CANCELLED.set(false);
        List<String> first = chat().prompt().user("long stream").stream().content()
                .take(3).collectList().block(Duration.ofSeconds(30));
        assertThat(first).hasSize(3);
        await(SERVER_CANCELLED::get, Duration.ofSeconds(15));
    }

    @Test
    @Timeout(60)
    void aStreamedToolCallRunsInTheCallersStreamingToolLoop() {
        int before = LOCAL_TOOL_CALLS.get();
        String text = String.join("", chat().prompt().user("stream a tool")
                .toolCallbacks(FunctionToolCallback.builder("local_lookup", (Lookup in) -> {
                    LOCAL_TOOL_CALLS.incrementAndGet();
                    return "found " + in.q();
                }).description("looks up locally").inputType(Lookup.class).build())
                .stream().content().collectList().block(Duration.ofSeconds(30)));
        assertThat(text).contains("thinking ").contains("done: \"found streamed\"");
        assertThat(LOCAL_TOOL_CALLS.get() - before).isEqualTo(1);
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
