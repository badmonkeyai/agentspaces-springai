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
import ai.badmonkey.agentspaces.api.space.Subscription;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.api.spi.Authorizer;
import ai.badmonkey.agentspaces.common.id.AgentId;
import ai.badmonkey.agentspaces.springai.model.wire.ModelCancel;
import ai.badmonkey.agentspaces.springai.model.wire.ModelChunk;
import ai.badmonkey.agentspaces.springai.model.wire.ModelRequest;
import ai.badmonkey.agentspaces.springai.model.wire.ModelResponse;
import ai.badmonkey.agentspaces.springai.model.wire.WireGeneration;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.scheduling.TaskScheduler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * A Spring AI {@link ChatModel} whose provider is the fleet. Each model
 * round-trip becomes a {@link ModelRequest} in a space; a {@code ModelServer} on
 * a peer that holds the credentials serves it and completes the take with the
 * response. The caller needs no API key and no provider dependency, a server
 * that dies mid-call lets its lease lapse and another serves the request, and
 * only responses from peers the {@link Authorizer} permits {@code MODEL_SERVE}
 * are accepted.
 *
 * <p>This relies on Spring AI 2.0's split between the model and the tool loop:
 * {@code ChatModel.call} performs one round-trip and never runs tools, so tool
 * calls come back in the assistant message and the caller's own
 * {@code ToolCallingAdvisor} runs them, with the caller's own tools.
 *
 * <p>{@link #stream(Prompt)} streams the server's deltas as short-leased chunk
 * entries, assembled in order by {@link ModelStreamAssembler}; cancelling the
 * {@code Flux} tells the server to stop.
 */
public class FleetChatModel implements ChatModel {

    /** The observation each fleet model call records. */
    public static final String OBSERVATION = "agentspaces.model.request";

    /** Metadata key on the marker response a stream emits when it restarts. */
    public static final String RESTART_KEY = "agentspaces.stream.restart";

    /** Response metadata: the peer that served the call. */
    public static final String SERVED_BY_KEY = "agentspaces.servedBy";

    /** Response metadata: the attempt that answered (1 unless a server died). */
    public static final String ATTEMPT_KEY = "agentspaces.attempt";

    private static final Duration POLL = Duration.ofMillis(200);

    private final Space space;
    private final Authorizer authorizer;
    private final String requester;
    private final String defaultModel;
    private final Duration timeout;
    private final StreamRestartPolicy restartPolicy;
    private final TaskScheduler scheduler;
    private final ObservationRegistry observations;

    /**
     * Creates the model.
     *
     * @param space         the requests space
     * @param authorizer    decides which servers' answers to accept
     * @param requester     this peer, for attribution
     * @param defaultModel  the model a prompt that names none asks for; empty for the server's default
     * @param timeout       how long one round-trip waits
     * @param restartPolicy what a stream does when its server dies
     * @param scheduler     runs stream read-throughs and timeouts
     * @param observations  the registry calls are observed on
     */
    public FleetChatModel(Space space, Authorizer authorizer, String requester, String defaultModel,
                          Duration timeout, StreamRestartPolicy restartPolicy, TaskScheduler scheduler,
                          ObservationRegistry observations) {
        this.space = Objects.requireNonNull(space, "space");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.requester = Objects.requireNonNull(requester, "requester");
        this.defaultModel = defaultModel == null ? "" : defaultModel;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.restartPolicy = Objects.requireNonNull(restartPolicy, "restartPolicy");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    /**
     * Tool-calling options: {@code ChatClient} attaches the caller's tools only
     * to a {@code ToolCallingChatOptions} builder, so the tool definitions reach
     * the request.
     */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().model(defaultModel.isBlank() ? null : defaultModel).build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ModelRequest request = request(prompt, false);
        return observation(request).observe(() -> {
            Template<ModelResponse> template = responses(request.requestId());
            CompletableFuture<ModelResponse> answer = new CompletableFuture<>();
            try (Subscription subscription = space.notify(template, (SpaceEvent<ModelResponse> event) -> {
                if (event.kind() == SpaceEvent.Kind.WRITTEN && trusted(event.issuer(), event.entry())) {
                    answer.complete(event.entry());
                }
            }, Lease.of(timeout.plusMinutes(1)))) {
                space.write(request, Lease.of(timeout.plusSeconds(30)));
                ModelResponse response = await(answer, template, Instant.now().plus(timeout))
                        .orElseThrow(() -> new FleetModelException("no trusted model server answered request "
                                + request.requestId() + " (model '" + request.model() + "') within " + timeout));
                retire(template);
                if (response.error() != null) {
                    throw new FleetModelException("model server " + response.servedBy() + " failed: "
                            + response.error());
                }
                return WireMapping.toResponse(response.generations(), response.usage(), response.model(),
                        response.requestId(), java.util.Map.of(SERVED_BY_KEY, response.servedBy(),
                                ATTEMPT_KEY, response.attempt()));
            }
        });
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ModelRequest request = request(prompt, true);
        return Flux.create(sink -> startStream(request, sink), FluxSink.OverflowStrategy.BUFFER);
    }

    private void startStream(ModelRequest request, FluxSink<ChatResponse> sink) {
        String id = request.requestId();
        ModelStreamAssembler assembler = new ModelStreamAssembler(id, restartPolicy,
                new ModelStreamAssembler.Listener() {
                    @Override
                    public void onChunk(ModelChunk chunk) {
                        emit(sink, chunk);
                    }

                    @Override
                    public void onRestart(int fromAttempt, int toAttempt) {
                        sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage(""))),
                                ChatResponseMetadata.builder().id(id).keyValue(RESTART_KEY, toAttempt).build()));
                    }

                    @Override
                    public void onComplete(ModelChunk last) {
                        sink.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        sink.error(error);
                    }
                });
        Template<ModelChunk> chunks = Template.of(ModelChunk.class).where("requestId", eq(id));
        Subscription subscription = space.notify(chunks, (SpaceEvent<ModelChunk> event) -> {
            if (event.kind() == SpaceEvent.Kind.WRITTEN && trustedChunk(event.issuer(), request)) {
                assembler.accept(event.entry());
            }
        }, Lease.of(timeout.plusMinutes(1)));
        ScheduledFuture<?> readThrough = scheduler.scheduleWithFixedDelay(() -> {
            for (var issued : space.readAllIssued(chunks, 1000)) {
                if (trustedChunk(issued.issuer(), request)) {
                    assembler.accept(issued.entry());
                }
            }
        }, Instant.now().plus(Duration.ofMillis(250)), Duration.ofMillis(250));
        ScheduledFuture<?> deadline = scheduler.schedule(() -> assembler.fail(new FleetModelException(
                "model stream " + id + " did not finish within " + timeout)), Instant.now().plus(timeout));
        sink.onCancel(() -> {
            if (!assembler.isDone()) {
                space.write(new ModelCancel(id, requester), Lease.of(Duration.ofMinutes(1)));
            }
        });
        sink.onDispose(() -> {
            subscription.close();
            readThrough.cancel(false);
            deadline.cancel(false);
            scheduler.schedule(() -> retire(responses(id)), Instant.now().plusSeconds(1));
        });
        space.write(request, Lease.of(timeout.plusSeconds(30)));
    }

    private void emit(FluxSink<ChatResponse> sink, ModelChunk chunk) {
        List<WireGeneration> deltas = chunk.deltas();
        for (int i = 0; i < deltas.size(); i++) {
            boolean final_ = chunk.last() && i == deltas.size() - 1;
            sink.next(WireMapping.toResponse(List.of(deltas.get(i)), final_ ? chunk.usage() : null,
                    null, chunk.requestId(), streamMetadata(chunk)));
        }
        if (chunk.last() && deltas.isEmpty() && chunk.usage() != null) {
            sink.next(WireMapping.toResponse(List.of(new WireGeneration(null, "STOP")), chunk.usage(),
                    null, chunk.requestId(), streamMetadata(chunk)));
        }
    }

    private static java.util.Map<String, Object> streamMetadata(ModelChunk chunk) {
        return chunk.servedBy() == null ? java.util.Map.of(ATTEMPT_KEY, chunk.attempt())
                : java.util.Map.of(SERVED_BY_KEY, chunk.servedBy(), ATTEMPT_KEY, chunk.attempt());
    }

    private ModelRequest request(Prompt prompt, boolean stream) {
        ChatOptions options = prompt.getOptions();
        String model = options != null && options.getModel() != null && !options.getModel().isBlank()
                ? options.getModel() : defaultModel;
        return new ModelRequest(UUID.randomUUID().toString(), model,
                WireMapping.toWire(prompt.getInstructions()), WireMapping.toWire(options),
                WireMapping.toolDefinitions(options), stream, requester);
    }

    private Observation observation(ModelRequest request) {
        return Observation.createNotStarted(OBSERVATION, observations)
                .lowCardinalityKeyValue("agentspaces.model", request.model().isBlank() ? "default" : request.model())
                .lowCardinalityKeyValue("agentspaces.stream", String.valueOf(request.stream()))
                .contextualName("fleet model request");
    }

    private Optional<ModelResponse> await(CompletableFuture<ModelResponse> answer,
                                          Template<ModelResponse> template, Instant deadline) {
        while (Instant.now().isBefore(deadline)) {
            try {
                return Optional.of(answer.get(POLL.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                for (var issued : space.readAllIssued(template, 16)) {
                    if (trusted(issued.issuer(), issued.entry())) {
                        return Optional.of(issued.entry());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FleetModelException("interrupted waiting for a model server");
            } catch (ExecutionException e) {
                throw new FleetModelException("model request failed: " + e.getCause());
            }
        }
        return Optional.empty();
    }

    private boolean trusted(AgentId issuer, ModelResponse response) {
        return issuer != null && authorizer.permits(issuer, Authorizer.Operation.MODEL_SERVE,
                response.model() == null ? "" : response.model());
    }

    private boolean trustedChunk(AgentId issuer, ModelRequest request) {
        return issuer != null && authorizer.permits(issuer, Authorizer.Operation.MODEL_SERVE, request.model());
    }

    /** Takes the answered response off the space: the caller has it, nobody else needs it. */
    private void retire(Template<ModelResponse> template) {
        try {
            Optional<? extends TakenEntry<ModelResponse>> taken =
                    space.take(template, Lease.of(Duration.ofSeconds(30)), Duration.ZERO);
            taken.ifPresent(space::complete);
        } catch (RuntimeException e) {
            // Best effort: the response's own lease collects it otherwise.
        }
    }

    private static Template<ModelResponse> responses(String requestId) {
        return Template.of(ModelResponse.class).where("requestId", eq(requestId));
    }
}
