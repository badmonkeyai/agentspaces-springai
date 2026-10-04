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
package ai.badmonkey.agentspaces.springai.tools;

import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/**
 * Refreshes the fleet tool set on a fixed interval, so a
 * {@link FleetToolsChangedEvent} fires when cards arrive or lapse even while no
 * model is calling. Runs on the application's {@link TaskScheduler}, after the
 * AgentSpaces node has started.
 */
public class FleetToolsRefresher implements SmartLifecycle {

    /** Starts after the AgentSpaces lifecycle ({@code Integer.MAX_VALUE - 1024}). */
    public static final int PHASE = Integer.MAX_VALUE - 768;

    private final FleetToolCallbackProvider provider;
    private final TaskScheduler scheduler;
    private final Duration interval;
    private volatile ScheduledFuture<?> task;

    /**
     * Creates the refresher.
     *
     * @param provider  the provider to refresh
     * @param scheduler the scheduler
     * @param interval  the refresh interval
     */
    public FleetToolsRefresher(FleetToolCallbackProvider provider, TaskScheduler scheduler,
                               Duration interval) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.interval = Objects.requireNonNull(interval, "interval");
    }

    @Override
    public void start() {
        task = scheduler.scheduleAtFixedRate(provider::refresh, interval);
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
        return PHASE;
    }
}
