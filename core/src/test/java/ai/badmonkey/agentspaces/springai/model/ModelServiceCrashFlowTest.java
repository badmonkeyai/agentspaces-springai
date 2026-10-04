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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 crash tolerance over TCP. A first server takes a request and dies
 * mid-work; its two-second lease lapses and a second server serves the request
 * as attempt 2. A blocking call never notices; a stream follows the caller's
 * {@link StreamRestartPolicy}: fail with the partial text, or restart.
 */
class ModelServiceCrashFlowTest {

    private static final String FOUNDING = "springai-model-crash-v1";
    static final AtomicReference<StreamRestartPolicy.Action> POLICY =
            new AtomicReference<>(StreamRestartPolicy.Action.FAIL);

    static TestPeer seed;
    static int seedPort;
    static ConfigurableApplicationContext caller;

    private static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** A server that takes the request, starts, and never finishes. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class DyingServer {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Bean
        ScriptedChatModel provider() {
            return new ScriptedChatModel(prompt -> {
                seen.add("call");
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("killed");
            }).streaming(prompt -> {
                seen.add("stream");
                return Flux.just("partial ", "text ", "from ", "one ").delayElements(Duration.ofMillis(100))
                        .map(ModelServiceCrashFlowTest::text).concatWith(Flux.never());
            });
        }
    }

    /** A healthy server. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class HealthyServer {
        @Bean
        ScriptedChatModel provider() {
            return ScriptedChatModel.answering("second server")
                    .streaming(prompt -> Flux.just("fresh ", "answer").map(ModelServiceCrashFlowTest::text));
        }
    }

    /** The caller, with a restart policy the test controls. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CallerApp {
        @Bean
        StreamRestartPolicy testRestartPolicy() {
            return (requestId, from, to, partial) -> POLICY.get();
        }
    }

    private static ConfigurableApplicationContext server(Class<?> app) {
        return new SpringApplicationBuilder(app)
                .properties(FleetApps.nodeProperties(FOUNDING, seedPort, "model-requests"))
                .properties(Map.of("agentspaces.springai.model-server.enabled", "true",
                        "agentspaces.springai.model-server.models", "flaky",
                        "agentspaces.springai.model-server.lease", "2s",
                        "agentspaces.springai.model-server.chunk-interval", "20ms"))
                .run();
    }

    @BeforeAll
    static void startFleet() throws Exception {
        seedPort = TestPeer.freePort();
        seed = TestPeer.start(FOUNDING, "test-fleet", seedPort, 0, "model-requests");
        caller = new SpringApplicationBuilder(CallerApp.class)
                .properties(FleetApps.nodeProperties(FOUNDING, seedPort, "model-requests"))
                .properties(Map.of("agentspaces.springai.model-client.enabled", "true",
                        "agentspaces.springai.model-client.timeout", "60s"))
                .run();
    }

    @AfterAll
    static void stopFleet() {
        caller.close();
        seed.close();
    }

    private static ChatClient chat() {
        return caller.getBean(ChatClient.Builder.class).build();
    }

    @Test
    @Timeout(120)
    void aBlockingCallSurvivesItsServersDeathAsAttemptTwo() throws Exception {
        ConfigurableApplicationContext dying = server(DyingServer.class);
        DyingServer first = dying.getBean(DyingServer.class);
        Thread.sleep(1500);
        CompletableFuture<ChatResponse> call = CompletableFuture.supplyAsync(
                () -> chat().prompt().user("survive this").call().chatResponse());
        await(() -> first.seen.contains("call"), Duration.ofSeconds(30));

        try (ConfigurableApplicationContext healthy = server(HealthyServer.class)) {
            dying.close();
            ChatResponse response = call.get(60, TimeUnit.SECONDS);
            assertThat(response.getResult().getOutput().getText()).isEqualTo("second server");
            assertThat((Integer) response.getMetadata().get(FleetChatModel.ATTEMPT_KEY)).isEqualTo(2);
            assertThat((String) response.getMetadata().get(FleetChatModel.SERVED_BY_KEY))
                    .isEqualTo(healthy.getBean(PeerIdentity.class).peerId().value());
        }
    }

    @Test
    @Timeout(120)
    void underFailAStreamWhoseServerDiesEndsWithThePartialText() throws Exception {
        POLICY.set(StreamRestartPolicy.Action.FAIL);
        Outcome outcome = streamThroughACrash();
        assertThat(outcome.error.get(60, TimeUnit.SECONDS)).isInstanceOf(FleetStreamRestartedException.class)
                .satisfies(e -> {
                    FleetStreamRestartedException restarted = (FleetStreamRestartedException) e;
                    assertThat(restarted.partialText()).startsWith("partial text ");
                    assertThat(restarted.fromAttempt()).isEqualTo(1);
                    assertThat(restarted.toAttempt()).isEqualTo(2);
                });
    }

    @Test
    @Timeout(120)
    void underRestartTheNewAttemptFollowsAMarker() throws Exception {
        POLICY.set(StreamRestartPolicy.Action.RESTART);
        Outcome outcome = streamThroughACrash();
        outcome.done.get(60, TimeUnit.SECONDS);
        String all = String.join("", outcome.texts);
        assertThat(all).startsWith("partial text ").endsWith("fresh answer");
        assertThat(outcome.markers).containsExactly(2);
    }

    /** What a stream that crossed a server's death produced. */
    record Outcome(List<String> texts, List<Integer> markers, CompletableFuture<Throwable> error,
                   CompletableFuture<Void> done) {
    }

    private Outcome streamThroughACrash() throws Exception {
        ConfigurableApplicationContext dying = server(DyingServer.class);
        Thread.sleep(1500);
        Outcome outcome = new Outcome(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(),
                new CompletableFuture<>(), new CompletableFuture<>());
        chat().prompt().user("stream through a crash").stream().chatResponse().subscribe(response -> {
            Object marker = response.getMetadata().get(FleetChatModel.RESTART_KEY);
            if (marker != null) {
                outcome.markers.add((Integer) marker);
            } else if (response.getResult() != null && response.getResult().getOutput().getText() != null) {
                outcome.texts.add(response.getResult().getOutput().getText());
            }
        }, outcome.error::complete, () -> outcome.done.complete(null));
        await(() -> String.join("", outcome.texts).startsWith("partial text "), Duration.ofSeconds(30));

        ConfigurableApplicationContext healthy = server(HealthyServer.class);
        try {
            dying.close();
            CompletableFuture.anyOf(outcome.error, outcome.done).get(60, TimeUnit.SECONDS);
        } finally {
            healthy.close();
        }
        return outcome;
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
