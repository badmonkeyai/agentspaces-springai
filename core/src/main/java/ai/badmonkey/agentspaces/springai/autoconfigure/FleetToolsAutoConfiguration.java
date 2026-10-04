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

import ai.badmonkey.agentspaces.agent.remote.RemoteActions;
import ai.badmonkey.agentspaces.springai.FleetContext;
import ai.badmonkey.agentspaces.springai.FleetTaskScheduler;
import ai.badmonkey.agentspaces.springai.tools.FleetAction;
import ai.badmonkey.agentspaces.springai.tools.FleetToolCallbackProvider;
import ai.badmonkey.agentspaces.springai.tools.FleetToolFilter;
import ai.badmonkey.agentspaces.springai.tools.FleetTools;
import ai.badmonkey.agentspaces.springai.tools.FleetToolsRefresher;
import ai.badmonkey.agentspaces.springai.tools.ToolNamingStrategy;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * F1: the fleet's AgentCards as Spring AI tools. Registers {@link FleetTools}
 * for applications to attach to their {@code ChatClient}
 * ({@code fleetTools.toolCallbacks()}), the naming and filtering it uses (each replaceable by
 * declaring a bean of its interface), and the refresher that publishes
 * {@code FleetToolsChangedEvent}s.
 */
@AutoConfiguration(after = AgentSpacesSpringAiAutoConfiguration.class)
@ConditionalOnClass(ToolCallbackProvider.class)
@ConditionalOnBean(FleetContext.class)
@ConditionalOnProperty(prefix = "agentspaces.springai.tools", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class FleetToolsAutoConfiguration {

    /**
     * The default tool naming: the configured prefix and the action's name.
     *
     * @param properties the properties
     * @return the strategy
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolNamingStrategy fleetToolNamingStrategy(AgentSpacesSpringAiProperties properties) {
        return ToolNamingStrategy.prefixed(properties.tools().prefix());
    }

    /**
     * The default filter: the configured include and exclude lists and the
     * attestation requirement.
     *
     * @param properties the properties
     * @return the filter
     */
    @Bean
    @ConditionalOnMissingBean
    public FleetToolFilter fleetToolFilter(AgentSpacesSpringAiProperties properties) {
        AgentSpacesSpringAiProperties.Tools tools = properties.tools();
        return FleetToolFilter.of(tools.includeAgents(), tools.excludeAgents(), tools.requireAttested());
    }

    /**
     * The fleet's actions, read from the group's advertisement cache.
     *
     * @param fleet the fleet context
     * @return the remote actions
     */
    @Bean
    @ConditionalOnMissingBean
    public RemoteActions fleetRemoteActions(FleetContext fleet) {
        return RemoteActions.over(fleet.group(), fleet.peerId());
    }

    /**
     * The fleet's live tool provider (not itself a bean of the
     * {@code ToolCallbackProvider} type the MCP server collects).
     *
     * @param actions      the fleet's remote actions
     * @param filter       the filter
     * @param naming       the naming strategy
     * @param properties   the properties
     * @param json         the application's JSON mapper, if any
     * @param observations the observation registry, if any
     * @param events       the application's event publisher
     * @return the fleet tools
     */
    @Bean
    @ConditionalOnMissingBean
    public FleetTools fleetTools(
            RemoteActions actions, FleetToolFilter filter, ToolNamingStrategy naming,
            AgentSpacesSpringAiProperties properties, ObjectProvider<JsonMapper> json,
            ObjectProvider<ObservationRegistry> observations, ApplicationEventPublisher events) {
        AgentSpacesSpringAiProperties.Tools tools = properties.tools();
        return new FleetTools(new FleetToolCallbackProvider(
                () -> actions.available().stream().map(FleetAction::of).toList(),
                filter, naming, json.getIfAvailable(JsonMapper::new), tools.timeout(),
                tools.onTimeout(), observations.getIfAvailable(() -> ObservationRegistry.NOOP),
                tools.cacheTtl(), events::publishEvent, Clock.systemUTC()));
    }

    /**
     * Refreshes the tool set on an interval, so change events fire between calls.
     *
     * @param tools      the fleet tools
     * @param scheduler  the scheduler
     * @param properties the properties
     * @return the refresher
     */
    @Bean
    @ConditionalOnMissingBean
    public FleetToolsRefresher fleetToolsRefresher(FleetTools tools,
                                                   FleetTaskScheduler scheduler,
                                                   AgentSpacesSpringAiProperties properties) {
        return new FleetToolsRefresher(tools.provider(), scheduler.scheduler(),
                properties.tools().refreshInterval());
    }
}
