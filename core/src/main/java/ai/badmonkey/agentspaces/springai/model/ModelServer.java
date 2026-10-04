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

import ai.badmonkey.agentspaces.api.space.ConflictStrategyType;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.SpaceEvent;
import ai.badmonkey.agentspaces.api.space.Subscription;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.space.replicated.ReplicatedSpace;
import ai.badmonkey.agentspaces.springai.model.wire.ModelAttempt;
import ai.badmonkey.agentspaces.springai.model.wire.ModelCancel;
import ai.badmonkey.agentspaces.springai.model.wire.ModelChunk;
import ai.badmonkey.agentspaces.springai.model.wire.ModelRequest;
import ai.badmonkey.agentspaces.springai.model.wire.ModelResponse;
import ai.badmonkey.agentspaces.springai.model.wire.WireGeneration;
import ai.badmonkey.agentspaces.springai.model.wire.WireMessage;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolCall;
import ai.badmonkey.agentspaces.springai.model.wire.WireUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * Serves the fleet's model requests with this peer's {@link ChatModel}s. Each
 * worker loop takes a {@link ModelRequest} (by model name, or by auction), stamps
 * the attempt, calls the provider, and completes the take with the response, so
 * the answer and the request's removal land together. The take lease is renewed
 * while the provider works; a server that dies lets it lapse and the request
 * goes to another server with the next attempt number.
 *
 * <p>A streamed request writes the provider's deltas as short-leased
 * {@link ModelChunk}s, batched by the {@link StreamChunkingPolicy}; a
 * {@link ModelCancel} stops the stream; and the full answer still completes the
 * take, so a late reader gets it whole. The server never runs tools: in Spring
 * AI 2.0 a {@code ChatModel} call returns tool calls to the caller.
 */
public class ModelServer implements SmartLifecycle {

    /** Starts after the AgentSpaces lifecycle ({@code Integer.MAX_VALUE - 1024}). */
    public static final int PHASE = Integer.MAX_VALUE - 768;

    private static final System.Logger LOG = System.getLogger(ModelServer.class.getName());

    /** The durations and limits a server works with. */
    public record Settings(Duration lease, Duration chunkLease, Duration responseLease, int concurrency) {
        /** Validates the values. */
        public Settings {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(chunkLease, "chunkLease");
            Objects.requireNonNull(responseLease, "responseLease");
            if (concurrency < 1) {
                throw new IllegalArgumentException("concurrency must be positive");
            }
        }
    }

    private final Space space;
    private final ModelCatalog catalog;
    private final ModelRequestRouter router;
    private final StreamChunkingPolicy chunking;
    private final TaskScheduler scheduler;
    private final String servedBy;
    private final Settings settings;
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final AtomicLong served = new AtomicLong();
    private volatile boolean running;

    /**
     * Creates the server.
     *
     * @param space     the requests space
     * @param catalog   the models served here
     * @param router    which requests to take
     * @param chunking  stream batching
     * @param scheduler lease renewals and stream flushes
     * @param servedBy  this peer, stamped on every answer
     * @param settings  durations and concurrency
     */
    public ModelServer(Space space, ModelCatalog catalog, ModelRequestRouter router,
                       StreamChunkingPolicy chunking, TaskScheduler scheduler, String servedBy,
                       Settings settings) {
        this.space = Objects.requireNonNull(space, "space");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.router = Objects.requireNonNull(router, "router");
        this.chunking = Objects.requireNonNull(chunking, "chunking");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.servedBy = Objects.requireNonNull(servedBy, "servedBy");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** How many requests this server has answered. */
    public long served() {
        return served.get();
    }

    @Override
    public void start() {
        if (router.bid(new ModelRequest("probe", "", List.of(), null, List.of(), false, ""), catalog).isPresent()) {
            if (!(space instanceof ReplicatedSpace replicated)
                    || replicated.strategy() != ConflictStrategyType.AUCTION) {
                throw new IllegalStateException("price routing needs the requests space '" + space.name()
                        + "' to run AUCTION: set strategy: AUCTION on it under agentspaces.groups[].spaces");
            }
            replicated.bidFunction(entry -> entry instanceof ModelRequest request
                    ? router.bid(request, catalog).orElse(Double.POSITIVE_INFINITY)
                    : Double.POSITIVE_INFINITY);
        }
        running = true;
        Template<ModelRequest> template = router.template(catalog);
        for (int i = 0; i < settings.concurrency(); i++) {
            workers.add(Thread.ofVirtual().name("model-server-" + i).start(() -> loop(template)));
        }
    }

    @Override
    public void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        workers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void loop(Template<ModelRequest> template) {
        while (running) {
            Optional<TakenEntry<ModelRequest>> taken;
            try {
                taken = space.take(template, Lease.of(settings.lease()), Duration.ofSeconds(1));
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                LOG.log(System.Logger.Level.WARNING, "model-server: take failed", e);
                continue;
            }
            taken.ifPresent(this::serve);
        }
    }

    private void serve(TakenEntry<ModelRequest> taken) {
        ModelRequest request = taken.entry();
        int attempt = nextAttempt(request.requestId());
        space.write(new ModelAttempt(request.requestId(), attempt, servedBy), Lease.of(Duration.ofMinutes(10)));
        String model = request.model().isBlank() ? catalog.defaultModel() : request.model();
        Duration renewEvery = settings.lease().dividedBy(3);
        ScheduledFuture<?> renewer = scheduler.scheduleAtFixedRate(() -> {
            try {
                taken.renew(settings.lease());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "model-server: renewal failed", e);
            }
        }, Instant.now().plus(renewEvery), renewEvery);
        try {
            Optional<ChatModel> chat = catalog.model(model);
            if (chat.isEmpty()) {
                answer(taken, failure(request, model, attempt, "model '" + model + "' is not served by " + servedBy));
                return;
            }
            Prompt prompt = new Prompt(WireMapping.fromWire(request.messages()),
                    WireMapping.fromWire(request.options(), model, request.tools()));
            if (request.stream()) {
                stream(taken, request, model, attempt, chat.get(), prompt);
            } else {
                ChatResponse response = chat.get().call(prompt);
                answer(taken, new ModelResponse(request.requestId(), model, WireMapping.generations(response),
                        WireMapping.usage(response), null, servedBy, attempt));
            }
        } catch (RuntimeException e) {
            if (!running) {
                return; // shutting down: leave the take to lapse for another server
            }
            answer(taken, failure(request, model, attempt, e.toString()));
        } finally {
            renewer.cancel(false);
        }
    }

    private void stream(TakenEntry<ModelRequest> taken, ModelRequest request, String model, int attempt,
                        ChatModel chat, Prompt prompt) {
        StreamWriter writer = new StreamWriter(request.requestId(), model, attempt);
        Subscription cancels = space.notify(Template.of(ModelCancel.class)
                        .where("requestId", eq(request.requestId())),
                (SpaceEvent<ModelCancel> event) -> {
                    if (event.kind() == SpaceEvent.Kind.WRITTEN) {
                        writer.cancel();
                    }
                }, Lease.of(settings.lease().plusMinutes(1)));
        ScheduledFuture<?> flusher = scheduler.scheduleAtFixedRate(writer::flushIfDue,
                Instant.now().plus(chunking.interval()), chunking.interval());
        try {
            writer.disposable = chat.stream(prompt).subscribe(writer::add, writer::fail, writer::finish);
            if (writer.cancelled.get()) {
                writer.disposable.dispose();
            }
            writer.done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.disposable.dispose();
            return; // shutting down mid-stream: the take lapses, the caller sees the restart
        } finally {
            flusher.cancel(false);
            cancels.close();
        }
        if (running) {
            answer(taken, writer.response());
        }
    }

    private void answer(TakenEntry<ModelRequest> taken, ModelResponse response) {
        space.complete(taken, response, Lease.of(settings.responseLease()));
        served.incrementAndGet();
    }

    private ModelResponse failure(ModelRequest request, String model, int attempt, String error) {
        return new ModelResponse(request.requestId(), model, List.of(), null, error, servedBy, attempt);
    }

    private int nextAttempt(String requestId) {
        return space.readAll(Template.of(ModelAttempt.class).where("requestId", eq(requestId)), 100).stream()
                .mapToInt(ModelAttempt::attempt).max().orElse(0) + 1;
    }

    /** One stream's batching, aggregation, and ending, guarded by its own lock. */
    private final class StreamWriter {
        private final String requestId;
        private final String model;
        private final int attempt;
        private final List<WireGeneration> buffer = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final Map<String, WireToolCall> toolCalls = new LinkedHashMap<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Disposable disposable = () -> { };
        private int seq;
        private Instant lastFlush = Instant.now();
        private WireUsage usage;
        private String finishReason;
        private String error;

        StreamWriter(String requestId, String model, int attempt) {
            this.requestId = requestId;
            this.model = model;
            this.attempt = attempt;
        }

        synchronized void add(ChatResponse response) {
            for (WireGeneration generation : WireMapping.generations(response)) {
                buffer.add(generation);
                WireMessage output = generation.output();
                if (output != null) {
                    if (output.text() != null) {
                        text.append(output.text());
                    }
                    for (WireToolCall call : output.toolCalls()) {
                        toolCalls.put(call.id() == null ? String.valueOf(toolCalls.size()) : call.id(), call);
                    }
                }
                if (generation.finishReason() != null) {
                    finishReason = generation.finishReason();
                }
            }
            WireUsage reported = WireMapping.usage(response);
            if (reported != null) {
                usage = reported;
            }
            if (chunking.flush(buffer.size(), Duration.between(lastFlush, Instant.now()))) {
                write(false);
            }
        }

        synchronized void flushIfDue() {
            if (!finished.get() && chunking.flush(buffer.size(), Duration.between(lastFlush, Instant.now()))) {
                write(false);
            }
        }

        void fail(Throwable failure) {
            synchronized (this) {
                error = failure.toString();
            }
            finish();
        }

        void cancel() {
            cancelled.set(true);
            disposable.dispose();
            finish();
        }

        void finish() {
            if (finished.compareAndSet(false, true)) {
                synchronized (this) {
                    write(true);
                }
                done.countDown();
            }
        }

        private void write(boolean last) {
            space.write(new ModelChunk(requestId, attempt, seq++, List.copyOf(buffer), last,
                    last ? usage : null, last ? error : null, servedBy), Lease.of(settings.chunkLease()));
            buffer.clear();
            lastFlush = Instant.now();
        }

        synchronized ModelResponse response() {
            WireMessage message = new WireMessage("assistant", text.toString(),
                    new ArrayList<>(toolCalls.values()), List.of(), List.of(), Map.of());
            return new ModelResponse(requestId, model, List.of(new WireGeneration(message, finishReason)),
                    usage, error, servedBy, attempt);
        }
    }
}
