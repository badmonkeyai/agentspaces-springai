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

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * The enabled {@link FleetDiscoveryTools} methods as a tool provider. Only the
 * tools named in {@code enabled} are exposed, so each can be switched on
 * separately.
 */
public class FleetDiscoveryToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallback[] callbacks;

    /**
     * Creates the provider.
     *
     * @param tools   the tools object
     * @param enabled the tool names to expose
     */
    public FleetDiscoveryToolCallbackProvider(FleetDiscoveryTools tools, Set<String> enabled) {
        Objects.requireNonNull(tools, "tools");
        Set<String> names = Set.copyOf(enabled);
        this.callbacks = Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build()
                        .getToolCallbacks())
                .filter(callback -> names.contains(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.clone();
    }
}
