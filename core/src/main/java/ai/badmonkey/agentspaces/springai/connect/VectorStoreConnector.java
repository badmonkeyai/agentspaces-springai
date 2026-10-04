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
package ai.badmonkey.agentspaces.springai.connect;

import ai.badmonkey.agentspaces.connect.ConnectorRuntime;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Serves a {@link VectorStoreAssetProvider} to the fleet for the life of the
 * application context: the connector SDK's {@link ConnectorRuntime} publishes
 * the store's asset card and answers queries from the data space. Starts after
 * the AgentSpaces lifecycle, like the model server, so the space it serves is
 * already replicating.
 */
public class VectorStoreConnector implements SmartLifecycle {

    /** Starts after the AgentSpaces lifecycle ({@code Integer.MAX_VALUE - 1024}). */
    public static final int PHASE = Integer.MAX_VALUE - 768;

    private final Supplier<ConnectorRuntime> runtimes;
    private ConnectorRuntime runtime;

    /**
     * Creates the connector.
     *
     * @param runtimes builds the runtime at start, once the space is live
     */
    public VectorStoreConnector(Supplier<ConnectorRuntime> runtimes) {
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    }

    @Override
    public synchronized void start() {
        if (runtime == null) {
            runtime = runtimes.get();
            runtime.start();
        }
    }

    @Override
    public synchronized void stop() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return runtime != null;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
