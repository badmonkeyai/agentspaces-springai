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

import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.SpaceEvent;
import ai.badmonkey.agentspaces.api.space.SpaceListener;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.api.spi.Authorizer;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.space.local.LocalSpace;
import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.RestartMode;
import ai.badmonkey.agentspaces.springai.model.wire.ModelCancel;
import ai.badmonkey.agentspaces.springai.model.wire.ModelChunk;
import ai.badmonkey.agentspaces.springai.model.wire.ModelRequest;
import ai.badmonkey.agentspaces.springai.model.wire.ModelResponse;
import ai.badmonkey.agentspaces.springai.model.wire.WireGeneration;
import ai.badmonkey.agentspaces.springai.model.wire.WireMessage;
import ai.badmonkey.agentspaces.springai.model.wire.WireUsage;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FleetChatModel and ModelServer over one LocalSpace, deterministic and with
 * no network: the request and response protocol, the stream's ordering and
 * cancellation checked with StepVerifier, the {@code agentspaces.model.request}
 * observation, and the server's lifecycle.
 */
class FleetChatModelLocalTest {

    private static final Authorizer PERMIT_ALL = (peer, operation, scope) -> true;

    private final LocalSpace space = LocalSpace.builder("model-requests", PeerIdentity.generate().agent("host"))
            .sweepEvery(Duration.ofMillis(50)).build();
    private final SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();

    @AfterEach
    void tearDown() {
        scheduler.close();
        space.close();
    }

    private FleetChatModel model(ObservationRegistry observations) {
        return model(space, observations);
    }

    private FleetChatModel model(Space space, ObservationRegistry observations) {
        return new FleetChatModel(space, PERMIT_ALL, "peer:caller", "m1", Duration.ofSeconds(10),
                StreamRestartPolicy.of(RestartMode.FAIL), scheduler, observations);
    }

    private ModelServer server(ScriptedChatModel provider) {
        return new ModelServer(space, ModelCatalog.of(List.of("m1"), Map.of("provider", provider), Map.of()),
                ModelRequestRouter.byModel(), StreamChunkingPolicy.of(Duration.ofMillis(20), 1), scheduler,
                "peer:server", new ModelServer.Settings(Duration.ofSeconds(30), Duration.ofMinutes(1),
                        Duration.ofMinutes(1), 1));
    }

    private static ModelChunk chunk(String requestId, int seq, boolean last, String text) {
        return new ModelChunk(requestId, 1, seq,
                List.of(new WireGeneration(new WireMessage("assistant", text, null, null, null, Map.of()), null)),
                last, last ? new WireUsage(1, 2, 3) : null, null, "peer:server");
    }

    @Test
    @Timeout(20)
    void aServedCallIsObservedAndTheServerStartsAfterTheFabricAndStopsCleanly() {
        ModelServer server = server(ScriptedChatModel.answering("served"));
        assertThat(server.getPhase()).as("after AgentSpacesLifecycle's MAX_VALUE - 1024")
                .isGreaterThan(Integer.MAX_VALUE - 1024);
        server.start();
        TestObservationRegistry observations = TestObservationRegistry.create();
        try {
            ChatResponse response = model(observations).call(new Prompt("hello"));
            assertThat(response.getResult().getOutput().getText()).isEqualTo("served");
            assertThat(server.served()).isEqualTo(1);
            TestObservationRegistryAssert.assertThat(observations)
                    .hasObservationWithNameEqualTo(FleetChatModel.OBSERVATION).that()
                    .hasLowCardinalityKeyValue("agentspaces.model", "m1")
                    .hasLowCardinalityKeyValue("agentspaces.stream", "false")
                    .hasBeenStopped();
        } finally {
            server.stop();
        }
        assertThat(server.isRunning()).isFalse();
        space.write(new ModelRequest("after-stop", "m1", List.of(), null, List.of(), false, "peer:caller"),
                Lease.of(Duration.ofMinutes(1)));
        sleep(1500);
        assertThat(space.read(Template.of(ModelRequest.class))).as("a stopped server takes nothing").isPresent();
    }

    @Test
    @Timeout(20)
    void chunksWrittenOutOfOrderStreamInOrderAndComplete() {
        StepVerifier.create(model(ObservationRegistry.NOOP).stream(new Prompt("stream")).map(this::text))
                .then(() -> {
                    TakenEntry<ModelRequest> taken = take();
                    String id = taken.entry().requestId();
                    assertThat(taken.entry().stream()).isTrue();
                    Lease lease = Lease.of(Duration.ofMinutes(1));
                    space.write(chunk(id, 1, false, "lo "), lease);
                    space.write(chunk(id, 0, false, "Hel"), lease);
                    space.write(chunk(id, 1, false, "lo "), lease); // a duplicate
                    space.write(chunk(id, 2, true, "fleet"), lease);
                })
                .expectNext("Hel", "lo ", "fleet")
                .verifyComplete();
    }

    @Test
    @Timeout(20)
    void cancellingAStreamWritesACancelForTheServer() {
        StepVerifier.create(model(ObservationRegistry.NOOP).stream(new Prompt("stream")).map(this::text))
                .then(() -> {
                    TakenEntry<ModelRequest> taken = take();
                    space.write(chunk(taken.entry().requestId(), 0, false, "first"), Lease.of(Duration.ofMinutes(1)));
                })
                .expectNext("first")
                .thenCancel()
                .verify(Duration.ofSeconds(10));
        Optional<ModelCancel> cancel = space.read(Template.of(ModelCancel.class), Duration.ofSeconds(5));
        assertThat(cancel).hasValueSatisfying(c -> assertThat(c.requester()).isEqualTo("peer:caller"));
    }

    /**
     * The read-through: a chunk whose notify event never reaches the caller (a
     * replica that missed it, a listener that lagged) still arrives, read from
     * the space on the 250 ms poll, and the gap it leaves holds the chunks
     * after it until it does.
     */
    @Test
    @Timeout(20)
    void aChunkWhoseEventIsMissedIsReadThroughAndTheStreamStaysInOrder() {
        Space missesChunkOne = missingEvents(chunk -> chunk.seq() == 1);
        StepVerifier.create(model(missesChunkOne, ObservationRegistry.NOOP).stream(new Prompt("stream"))
                        .map(this::text))
                .then(() -> {
                    String id = take().entry().requestId();
                    Lease lease = Lease.of(Duration.ofMinutes(1));
                    space.write(chunk(id, 0, false, "Hel"), lease);
                    space.write(chunk(id, 1, false, "lo "), lease); // its event is dropped
                    space.write(chunk(id, 2, true, "fleet"), lease);
                })
                .expectNext("Hel", "lo ", "fleet")
                .verifyComplete();
    }

    /** The stream honors demand: nothing is emitted before it is requested, and then exactly what is. */
    @Test
    @Timeout(20)
    void aSlowSubscriberGetsOnlyWhatItRequestsInOrder() {
        StepVerifier.create(model(ObservationRegistry.NOOP).stream(new Prompt("stream")).map(this::text), 0)
                .expectSubscription()
                .then(() -> {
                    String id = take().entry().requestId();
                    Lease lease = Lease.of(Duration.ofMinutes(1));
                    space.write(chunk(id, 0, false, "Hel"), lease);
                    space.write(chunk(id, 1, false, "lo "), lease);
                    space.write(chunk(id, 2, true, "fleet"), lease);
                })
                .expectNoEvent(Duration.ofMillis(600))
                .thenRequest(1)
                .expectNext("Hel")
                .expectNoEvent(Duration.ofMillis(300))
                .thenRequest(2)
                .expectNext("lo ", "fleet")
                .verifyComplete();
    }

    /**
     * Cancelling after three chunks of a served stream stops the server's
     * provider, and the server still completes: a last chunk ends the stream
     * for any other reader, and the take completes with the partial answer.
     */
    @Test
    @Timeout(30)
    void cancellingAfterThreeChunksStopsTheServerWhichStillCompletes() {
        AtomicBoolean providerCancelled = new AtomicBoolean();
        ModelServer server = server(ScriptedChatModel.answering("unused").streaming(prompt ->
                Flux.range(0, 400).delayElements(Duration.ofMillis(25))
                        .map(i -> new ChatResponse(List.of(new Generation(new AssistantMessage("x" + i)))))
                        .doOnCancel(() -> providerCancelled.set(true))));
        server.start();
        try {
            StepVerifier.create(model(ObservationRegistry.NOOP).stream(new Prompt("long stream")).map(this::text))
                    .expectNext("x0", "x1", "x2")
                    .thenCancel()
                    .verify(Duration.ofSeconds(15));

            Optional<ModelResponse> response = space.read(Template.of(ModelResponse.class), Duration.ofSeconds(10));
            assertThat(response).as("the take completed with the partial answer").hasValueSatisfying(r -> {
                assertThat(r.error()).isNull();
                assertThat(r.servedBy()).isEqualTo("peer:server");
            });
            assertThat(providerCancelled).as("the provider's stream was stopped").isTrue();
            assertThat(server.served()).isEqualTo(1);
            String id = response.get().requestId();
            assertThat(space.readAll(Template.of(ModelChunk.class), 1000))
                    .filteredOn(chunk -> chunk.requestId().equals(id))
                    .as("a last chunk ends the stream").anyMatch(ModelChunk::last);
            assertThat(space.read(Template.of(ModelRequest.class))).as("the request is gone").isEmpty();
        } finally {
            server.stop();
        }
    }

    /** The test space, with the notify events of matching chunks dropped. */
    @SuppressWarnings("unchecked")
    private Space missingEvents(java.util.function.Predicate<ModelChunk> dropped) {
        return (Space) Proxy.newProxyInstance(Space.class.getClassLoader(), new Class<?>[] {Space.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("notify") && args != null && args.length == 3
                            && args[1] instanceof SpaceListener<?> listener) {
                        SpaceListener<Object> inner = (SpaceListener<Object>) listener;
                        args[1] = (SpaceListener<Object>) event -> {
                            if (!(event.entry() instanceof ModelChunk chunk && dropped.test(chunk))) {
                                inner.onEvent((SpaceEvent<Object>) event);
                            }
                        };
                    }
                    try {
                        return method.invoke(space, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private String text(ChatResponse response) {
        return response.getResult().getOutput().getText();
    }

    private TakenEntry<ModelRequest> take() {
        return space.take(Template.of(ModelRequest.class), Lease.of(Duration.ofMinutes(1)), Duration.ofSeconds(5))
                .orElseThrow();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
