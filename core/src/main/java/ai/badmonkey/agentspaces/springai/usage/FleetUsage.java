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
package ai.badmonkey.agentspaces.springai.usage;

import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The default {@link UsageSink}. It keeps this peer's token counts per model and
 * per agent, optionally writes each call as a leased {@link ModelUsage} entry
 * for audit, and on every publish joins a push-sum SUM epoch per model with its
 * running total, so any peer reads the fleet's total token use with no
 * collector. The epoch is named by model and time window, which every peer
 * derives the same way from the shared interval.
 */
public class FleetUsage implements UsageSink {

    /** Epoch names: {@code agentspaces.usage.<model>.<window>}. */
    public static final String EPOCH_PREFIX = "agentspaces.usage.";

    private final PushSumAggregate aggregate;
    private final Space metrics;
    private final Lease entryLease;
    private final Duration interval;
    private final Clock clock;
    private final Map<String, LongAdder> byModel = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> byAgent = new ConcurrentHashMap<>();

    /**
     * Creates the sink.
     *
     * @param aggregate  the group's push-sum aggregate, or null for local counts only
     * @param metrics    the space usage entries go to, or null for none
     * @param entryLease the lease of usage entries
     * @param interval   the publish interval, which names the epochs
     * @param clock      the clock windows are measured on
     */
    public FleetUsage(PushSumAggregate aggregate, Space metrics, Duration entryLease, Duration interval, Clock clock) {
        this.aggregate = aggregate;
        this.metrics = metrics;
        this.entryLease = Lease.of(Objects.requireNonNull(entryLease, "entryLease"));
        this.interval = Objects.requireNonNull(interval, "interval");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void record(ModelUsage usage) {
        byModel.computeIfAbsent(usage.model(), k -> new LongAdder()).add(usage.totalTokens());
        byAgent.computeIfAbsent(usage.agent().isEmpty() ? "(unattributed)" : usage.agent(),
                k -> new LongAdder()).add(usage.totalTokens());
        if (metrics != null) {
            metrics.write(usage, entryLease);
        }
    }

    /** Joins this window's SUM epoch for every model with this peer's running total. */
    public void publish() {
        if (aggregate == null) {
            return;
        }
        long window = window();
        byModel.forEach((model, total) ->
                aggregate.start(epoch(model, window), PushSumAggregate.Mode.SUM, total.sum()));
    }

    /**
     * The fleet's total tokens for a model: the newest window whose epoch has
     * an estimate.
     *
     * @param model the model
     * @return the estimate, or empty before any exchange
     */
    public OptionalDouble fleetTotal(String model) {
        if (aggregate == null) {
            return OptionalDouble.empty();
        }
        long window = window();
        for (long w = window; w >= window - 2; w--) {
            OptionalDouble estimate = aggregate.estimate(epoch(model, w));
            if (estimate.isPresent()) {
                return estimate;
            }
        }
        return OptionalDouble.empty();
    }

    /** This peer's total tokens per model. */
    public Map<String, Long> localByModel() {
        return snapshot(byModel);
    }

    /** This peer's total tokens per agent. */
    public Map<String, Long> localByAgent() {
        return snapshot(byAgent);
    }

    private long window() {
        return clock.millis() / interval.toMillis();
    }

    private static String epoch(String model, long window) {
        return EPOCH_PREFIX + model + "." + window;
    }

    private static Map<String, Long> snapshot(Map<String, LongAdder> counts) {
        Map<String, Long> out = new TreeMap<>();
        counts.forEach((key, value) -> out.put(key, value.sum()));
        return out;
    }
}
