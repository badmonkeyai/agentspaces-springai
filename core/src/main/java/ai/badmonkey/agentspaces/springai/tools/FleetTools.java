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

import java.util.List;
import java.util.Objects;

/**
 * The fleet's tools, as the bean applications inject. Attach them to a
 * {@code ChatClient} explicitly:
 *
 * <pre>{@code
 * ChatClient chat = builder.defaultToolCallbacks(fleetTools.toolCallbacks()).build();
 * }</pre>
 *
 * <p>This bean is deliberately not a {@link ToolCallbackProvider} itself. Spring
 * AI's MCP server auto-configuration publishes every {@code ToolCallbackProvider}
 * bean to outside MCP clients, and the fleet crosses that trust boundary only
 * through the MCP export (F7), which exposes nothing until a deployment names
 * the agents it may.
 */
public class FleetTools {

    private final FleetToolCallbackProvider provider;

    /**
     * Wraps a provider.
     *
     * @param provider the live fleet tool provider
     */
    public FleetTools(FleetToolCallbackProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /** The live tool set, to attach to a ChatClient; each request sees the current fleet. */
    public ToolCallbackProvider toolCallbacks() {
        return provider;
    }

    /** The current tool set. */
    public List<ToolCallback> current() {
        return provider.current();
    }

    /** Rebuilds the tool set now and reports any change. */
    public List<ToolCallback> refresh() {
        return provider.refresh();
    }

    /** The underlying provider. */
    public FleetToolCallbackProvider provider() {
        return provider;
    }
}
