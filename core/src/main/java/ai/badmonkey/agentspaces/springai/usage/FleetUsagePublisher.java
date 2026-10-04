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

import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/** Publishes this peer's usage into the fleet-wide sums on the configured interval. */
public class FleetUsagePublisher implements SmartLifecycle {

    private final FleetUsage usage;
    private final TaskScheduler scheduler;
    private final Duration interval;
    private volatile ScheduledFuture<?> task;

    /**
     * Creates the publisher.
     *
     * @param usage     the usage to publish
     * @param scheduler the scheduler
     * @param interval  the interval
     */
    public FleetUsagePublisher(FleetUsage usage, TaskScheduler scheduler, Duration interval) {
        this.usage = Objects.requireNonNull(usage, "usage");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.interval = Objects.requireNonNull(interval, "interval");
    }

    @Override
    public void start() {
        task = scheduler.scheduleAtFixedRate(usage::publish, interval);
    }

    @Override
    public void stop() {
        ScheduledFuture<?> running = task;
        if (running != null) {
            running.cancel(false);
            task = null;
        }
    }

    @Override
    public boolean isRunning() {
        return task != null;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 768;
    }
}
