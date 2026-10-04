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

import ai.badmonkey.agentspaces.agent.capability.SemanticClient;
import ai.badmonkey.agentspaces.connect.DataQueryClient;
import ai.badmonkey.agentspaces.springai.FleetContext;
import ai.badmonkey.agentspaces.springai.tools.FleetDiscoveryTools;
import ai.badmonkey.agentspaces.springai.tools.FleetTools;
import ai.badmonkey.agentspaces.springai.tools.RemoteActionToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F2: tools that reason about the fleet: {@code find_fleet_agents},
 * {@code list_fleet_assets}, and {@code fetch_fleet_data}, each enabled by its
 * own property. The group's semantic-discovery capability answers the first
 * two; the configured data space carries the third.
 */
@AutoConfiguration(after = FleetToolsAutoConfiguration.class)
@ConditionalOnClass(ToolCallbackProvider.class)
@ConditionalOnBean(FleetContext.class)
@ConditionalOnExpression("${agentspaces.springai.discovery-tools.agents:false}"
        + " or ${agentspaces.springai.discovery-tools.assets:false}"
        + " or ${agentspaces.springai.discovery-tools.data:false}")
public class FleetDiscoveryToolsAutoConfiguration {

    /**
     * The discovery and data tools.
     *
     * @param fleet      the fleet context
     * @param properties the properties
     * @param fleetTools the F1 provider, if enabled, to name each agent's tool
     * @param json       the application's JSON mapper, if any
     * @return the tools
     */
    @Bean
    @ConditionalOnMissingBean
    public FleetDiscoveryTools fleetDiscoveryTools(FleetContext fleet,
                                                   AgentSpacesSpringAiProperties properties,
                                                   ObjectProvider<FleetTools> fleetTools,
                                                   ObjectProvider<JsonMapper> json) {
        AgentSpacesSpringAiProperties.DiscoveryTools config = properties.discoveryTools();
        FleetDiscoveryTools.Search search = null;
        if (config.agents() || config.assets()) {
            SemanticClient semantic = fleet.group().capability(SemanticClient.class);
            search = (question, limit) -> semantic.remoteQuery(question, limit, config.timeout());
        }
        FleetDiscoveryTools.Fetch fetch = null;
        if (config.data()) {
            DataQueryClient client = new DataQueryClient(
                    fleet.requiredSpace(config.dataSpace(), "agentspaces.springai.discovery-tools.data-space"),
                    fleet.peerId().value(), fleet.authorizer());
            fetch = (asset, parameters) -> client.fetch(asset, parameters, config.timeout());
        }
        FleetTools provider = fleetTools.getIfAvailable();
        return new FleetDiscoveryTools(search, fetch,
                () -> provider == null ? List.<RemoteActionToolCallback>of()
                        : provider.current().stream()
                                .filter(RemoteActionToolCallback.class::isInstance)
                                .map(RemoteActionToolCallback.class::cast).toList(),
                json.getIfAvailable(JsonMapper::new), config.maxResultChars(), enabled(config));
    }

    private static Set<String> enabled(AgentSpacesSpringAiProperties.DiscoveryTools config) {
        Set<String> enabled = new LinkedHashSet<>();
        if (config.agents()) {
            enabled.add("find_fleet_agents");
        }
        if (config.assets()) {
            enabled.add("list_fleet_assets");
        }
        if (config.data()) {
            enabled.add("fetch_fleet_data");
        }
        return enabled;
    }
}
