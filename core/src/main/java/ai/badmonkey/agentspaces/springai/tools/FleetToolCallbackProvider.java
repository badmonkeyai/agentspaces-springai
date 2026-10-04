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

import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.OnTimeout;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The fleet's AgentCards as Spring AI tools. Attach it to a {@code ChatClient}
 * ({@code builder.defaultToolCallbacks(provider)}) and the model's tool loop can
 * call agents anywhere on the peer-to-peer fleet.
 *
 * <p>The tool set is live: each request sees a snapshot of the actions the
 * fleet advertises right now, cached for a short interval so a busy client does
 * not rescan the advertisement cache on every call. {@link #refresh()} rebuilds
 * the snapshot and reports what changed through the change listener, which the
 * auto-configuration connects to the application's event publisher.
 */
public class FleetToolCallbackProvider implements ToolCallbackProvider {

    private final Supplier<List<FleetAction>> actions;
    private final FleetToolFilter filter;
    private final ToolNamingStrategy naming;
    private final JsonMapper json;
    private final Duration timeout;
    private final OnTimeout onTimeout;
    private final ObservationRegistry observations;
    private final Duration cacheTtl;
    private final Consumer<FleetToolsChangedEvent> changes;
    private final Clock clock;

    private List<ToolCallback> snapshot = List.of();
    private Set<String> names = Set.of();
    private Instant snapshotAt = Instant.MIN;

    /**
     * Creates the provider.
     *
     * @param actions      supplies the fleet's current actions
     * @param filter       decides which actions become tools
     * @param naming       names each tool
     * @param json         the JSON mapper tool calls use
     * @param timeout      how long one tool call waits
     * @param onTimeout    what a timeout returns
     * @param observations the registry tool calls are observed on
     * @param cacheTtl     how long a snapshot serves requests
     * @param changes      receives an event whenever the tool set changes
     * @param clock        the clock the cache is measured on
     */
    public FleetToolCallbackProvider(Supplier<List<FleetAction>> actions, FleetToolFilter filter,
                                     ToolNamingStrategy naming, JsonMapper json, Duration timeout,
                                     OnTimeout onTimeout, ObservationRegistry observations,
                                     Duration cacheTtl, Consumer<FleetToolsChangedEvent> changes,
                                     Clock clock) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.naming = Objects.requireNonNull(naming, "naming");
        this.json = Objects.requireNonNull(json, "json");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.onTimeout = Objects.requireNonNull(onTimeout, "onTimeout");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return current().toArray(ToolCallback[]::new);
    }

    /** The current tool set, refreshed when the cached snapshot is older than the cache interval. */
    public synchronized List<ToolCallback> current() {
        if (!clock.instant().isBefore(snapshotAt.plus(cacheTtl))) {
            refresh();
        }
        return snapshot;
    }

    /**
     * Rebuilds the tool set from the fleet's current actions and reports any
     * change.
     *
     * @return the new tool set
     */
    public synchronized List<ToolCallback> refresh() {
        Map<String, ToolCallback> built = new LinkedHashMap<>();
        for (FleetAction action : actions.get()) {
            if (!filter.include(action)) {
                continue;
            }
            String base = naming.toolName(action);
            String name = base;
            for (int i = 2; built.containsKey(name); i++) {
                String suffix = "_" + i;
                name = (base.length() + suffix.length() > ToolNamingStrategy.MAX_LENGTH
                        ? base.substring(0, ToolNamingStrategy.MAX_LENGTH - suffix.length())
                        : base) + suffix;
            }
            built.put(name, new RemoteActionToolCallback(action, name, json, timeout, onTimeout,
                    observations));
        }
        List<ToolCallback> fresh = List.copyOf(built.values());
        Set<String> freshNames = Set.copyOf(built.keySet());
        Set<String> added = new LinkedHashSet<>(freshNames);
        added.removeAll(names);
        Set<String> removed = new LinkedHashSet<>(names);
        removed.removeAll(freshNames);
        snapshot = fresh;
        names = freshNames;
        snapshotAt = clock.instant();
        if (!added.isEmpty() || !removed.isEmpty()) {
            changes.accept(new FleetToolsChangedEvent(added, removed, new ArrayList<>(fresh)));
        }
        return snapshot;
    }
}
