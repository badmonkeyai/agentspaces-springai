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
package ai.badmonkey.agentspaces.springai.worker;

import ai.badmonkey.agentspaces.agent.TakeContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.scheduling.TaskScheduler;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

/**
 * Renews the in-flight take lease on every model round-trip. Ordered after
 * Spring AI's {@code ToolCallingAdvisor}, it runs inside the tool loop, once per
 * round-trip; a streamed round-trip also renews on an interval while it runs. A
 * worker making progress keeps its claim however long its agentic loop takes;
 * a worker that crashes or hangs stops renewing, and its task reappears one
 * lease later. Leases can therefore be short, which makes recovery fast.
 *
 * <p>The advisor does nothing when no take is in flight, so it is safe on every
 * {@code ChatClient}; the auto-configuration adds it to all of them.
 */
public class TakeLeaseAdvisor implements CallAdvisor, StreamAdvisor {

    /** Inside the tool loop: after {@code ToolCallingAdvisor}. */
    public static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 100;

    private static final System.Logger LOG = System.getLogger(TakeLeaseAdvisor.class.getName());

    private final TaskScheduler scheduler;
    private final Duration streamRenewInterval;
    private final Supplier<Optional<TakeContext>> current;

    /**
     * Creates the advisor over the worker loop's thread-bound take.
     *
     * @param scheduler           the scheduler stream renewals run on
     * @param streamRenewInterval the renewal interval while a stream runs
     */
    public TakeLeaseAdvisor(TaskScheduler scheduler, Duration streamRenewInterval) {
        this(scheduler, streamRenewInterval, TakeContext::current);
    }

    /**
     * Creates the advisor over a supplied take, for tests and custom hosts.
     *
     * @param scheduler           the scheduler stream renewals run on
     * @param streamRenewInterval the renewal interval while a stream runs
     * @param current             supplies the take in flight, if any
     */
    public TakeLeaseAdvisor(TaskScheduler scheduler, Duration streamRenewInterval,
                            Supplier<Optional<TakeContext>> current) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.streamRenewInterval = Objects.requireNonNull(streamRenewInterval, "streamRenewInterval");
        this.current = Objects.requireNonNull(current, "current");
    }

    @Override
    public String getName() {
        return "agentspaces.take-lease";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        current.get().ifPresent(TakeLeaseAdvisor::renew);
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Optional<TakeContext> captured = current.get();
        return Flux.deferContextual(view -> {
            TakeContext take = captured.orElseGet(
                    () -> view.<TakeContext>getOrEmpty(TakeContextAccessor.KEY).orElse(null));
            if (take == null) {
                return chain.nextStream(request);
            }
            renew(take);
            ScheduledFuture<?> ticker = scheduler.scheduleAtFixedRate(() -> renew(take),
                    Instant.now().plus(streamRenewInterval), streamRenewInterval);
            return chain.nextStream(request).doFinally(signal -> ticker.cancel(false));
        });
    }

    private static void renew(TakeContext take) {
        try {
            take.renew();
        } catch (RuntimeException e) {
            // The take may already be complete or lapsed; the worker loop decides.
            LOG.log(System.Logger.Level.DEBUG, "take lease renewal failed", e);
        }
    }
}
