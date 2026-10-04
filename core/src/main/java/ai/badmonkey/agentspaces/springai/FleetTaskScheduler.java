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
package ai.badmonkey.agentspaces.springai;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import java.util.Objects;

/**
 * The scheduler this project's periodic work runs on: the application's own
 * {@link TaskScheduler} when it defines one, otherwise a virtual-thread
 * scheduler this bean owns and closes. It is deliberately not a
 * {@code TaskScheduler} bean itself, so it never takes over an application's
 * {@code @EnableScheduling}.
 */
public class FleetTaskScheduler implements DisposableBean {

    private final TaskScheduler scheduler;
    private final SimpleAsyncTaskScheduler owned;

    /**
     * Uses the application's scheduler, or creates one when it is null.
     *
     * @param application the application's scheduler, or null
     */
    public FleetTaskScheduler(TaskScheduler application) {
        if (application != null) {
            this.scheduler = application;
            this.owned = null;
        } else {
            SimpleAsyncTaskScheduler created = new SimpleAsyncTaskScheduler();
            created.setVirtualThreads(true);
            created.setThreadNamePrefix("agentspaces-springai-");
            created.start();
            this.scheduler = created;
            this.owned = created;
        }
    }

    /** The scheduler. */
    public TaskScheduler scheduler() {
        return Objects.requireNonNull(scheduler);
    }

    @Override
    public void destroy() {
        if (owned != null) {
            owned.close();
        }
    }
}
