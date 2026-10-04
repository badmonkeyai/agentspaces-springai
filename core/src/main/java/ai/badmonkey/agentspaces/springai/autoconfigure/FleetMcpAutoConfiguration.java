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
package ai.badmonkey.agentspaces.springai.autoconfigure;

import ai.badmonkey.agentspaces.springai.mcp.FleetMcpToolSync;
import ai.badmonkey.agentspaces.springai.tools.FleetDiscoveryTools;
import ai.badmonkey.agentspaces.springai.tools.FleetTools;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.List;

/**
 * F7: publishes the fleet's tools through the application's Spring AI MCP
 * server and keeps them in step as the fleet changes. Needs the MCP server
 * starter on the classpath and {@code agentspaces.springai.mcp.enabled=true};
 * exposes only the agents named in {@code agentspaces.springai.mcp.include-agents}.
 */
@AutoConfiguration(after = {FleetToolsAutoConfiguration.class, FleetDiscoveryToolsAutoConfiguration.class},
        afterName = "org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration")
@ConditionalOnClass({McpSyncServer.class, McpToolUtils.class})
@ConditionalOnBean({McpSyncServer.class, FleetTools.class})
@ConditionalOnProperty(prefix = "agentspaces.springai.mcp", name = "enabled", havingValue = "true")
public class FleetMcpAutoConfiguration {

    /**
     * The sync between the fleet and the MCP server.
     *
     * @param server     the MCP server
     * @param tools      the fleet's tools
     * @param discovery  the discovery tools, if enabled
     * @param properties the properties
     * @return the sync
     */
    @Bean
    @ConditionalOnMissingBean
    public FleetMcpToolSync fleetMcpToolSync(McpSyncServer server, FleetTools tools,
                                             ObjectProvider<FleetDiscoveryTools> discovery,
                                             AgentSpacesSpringAiProperties properties) {
        AgentSpacesSpringAiProperties.Mcp mcp = properties.mcp();
        FleetDiscoveryTools discoveryTools = discovery.getIfAvailable();
        List<ToolCallback> extra = mcp.discoveryTools() && discoveryTools != null
                ? Arrays.asList(discoveryTools.toolCallbacks().getToolCallbacks()) : List.of();
        return new FleetMcpToolSync(server, tools, mcp.includeAgents(), extra);
    }
}
