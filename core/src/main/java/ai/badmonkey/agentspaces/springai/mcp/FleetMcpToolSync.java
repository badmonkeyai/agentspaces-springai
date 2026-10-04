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
package ai.badmonkey.agentspaces.springai.mcp;

import ai.badmonkey.agentspaces.springai.tools.FleetToolFilter;
import ai.badmonkey.agentspaces.springai.tools.FleetTools;
import ai.badmonkey.agentspaces.springai.tools.FleetToolsChangedEvent;
import ai.badmonkey.agentspaces.springai.tools.RemoteActionToolCallback;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps an MCP server's tools in step with the fleet. On start it publishes the
 * fleet tools a deployment chose to expose; on every {@link FleetToolsChangedEvent}
 * it adds and removes tools on the {@link McpSyncServer} and sends MCP's
 * {@code notifications/tools/list_changed}, so connected clients such as Claude
 * Desktop or an IDE agent refresh their tool list as cards arrive and lapse.
 *
 * <p>MCP clients sit outside the fleet's trust boundary, so nothing is exposed
 * until the deployment names the agents it may expose.
 */
public class FleetMcpToolSync implements SmartLifecycle {

    private final McpSyncServer server;
    private final FleetTools tools;
    private final Set<String> includeAgents;
    private final List<ToolCallback> extraTools;
    private final Set<String> published = new LinkedHashSet<>();
    private volatile boolean running;

    /**
     * Creates the sync.
     *
     * @param server        the MCP server
     * @param tools         the fleet's tools
     * @param includeAgents fleet agents (local names or AgentIds) to expose; empty exposes none
     * @param extraTools    further tools to expose as they are, such as the discovery tools
     */
    public FleetMcpToolSync(McpSyncServer server, FleetTools tools, List<String> includeAgents,
                            List<ToolCallback> extraTools) {
        this.server = Objects.requireNonNull(server, "server");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.includeAgents = Set.copyOf(includeAgents);
        this.extraTools = List.copyOf(extraTools);
    }

    @Override
    public void start() {
        running = true;
        sync(tools.current());
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 512;
    }

    /**
     * Applies a change in the fleet's tools.
     *
     * @param event the change
     */
    @EventListener
    public void onFleetToolsChanged(FleetToolsChangedEvent event) {
        if (running) {
            sync(event.current());
        }
    }

    /** The tool names currently published to the MCP server. */
    public synchronized Set<String> published() {
        return Set.copyOf(published);
    }

    private synchronized void sync(List<ToolCallback> current) {
        Map<String, ToolCallback> desired = new LinkedHashMap<>();
        if (!includeAgents.isEmpty()) {
            FleetToolFilter filter = FleetToolFilter.of(new ArrayList<>(includeAgents), List.of(), false);
            for (ToolCallback callback : current) {
                if (callback instanceof RemoteActionToolCallback fleet && filter.include(fleet.action())) {
                    desired.put(callback.getToolDefinition().name(), callback);
                }
            }
        }
        for (ToolCallback callback : extraTools) {
            desired.put(callback.getToolDefinition().name(), callback);
        }
        boolean changed = false;
        for (String name : new ArrayList<>(published)) {
            if (!desired.containsKey(name)) {
                server.removeTool(name);
                published.remove(name);
                changed = true;
            }
        }
        for (Map.Entry<String, ToolCallback> entry : desired.entrySet()) {
            if (!published.contains(entry.getKey())) {
                server.addTool(McpToolUtils.toSyncToolSpecification(entry.getValue()));
                published.add(entry.getKey());
                changed = true;
            }
        }
        if (changed) {
            server.notifyToolsListChanged();
        }
    }
}
