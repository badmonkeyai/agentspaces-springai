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

import ai.badmonkey.agentspaces.agent.AgentSpaces;
import ai.badmonkey.agentspaces.api.spi.Authorizer;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.spring.AgentSpacesProperties;
import ai.badmonkey.agentspaces.springai.FleetContext;
import ai.badmonkey.agentspaces.springai.FleetTaskScheduler;
import org.springframework.ai.chat.model.ChatModel;
import ai.badmonkey.agentspaces.capabilities.semantic.SemanticDiscovery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;

/**
 * The shared base of every agentspaces-springai feature: the properties, the
 * {@link FleetContext} each feature resolves its group and spaces through, and
 * the scheduler periodic work runs on. Each feature has its own
 * auto-configuration, ordered after this one, so an application that excludes
 * one feature keeps the rest.
 */
@AutoConfiguration(afterName = "ai.badmonkey.agentspaces.spring.AgentSpacesAutoConfiguration")
@ConditionalOnClass(ChatModel.class)
@ConditionalOnBean(AgentSpaces.class)
@EnableConfigurationProperties(AgentSpacesSpringAiProperties.class)
public class AgentSpacesSpringAiAutoConfiguration {

    /**
     * The group, identity, and authorizer every feature works through.
     *
     * @param spaces      the node's facade
     * @param starter     the starter's properties, for the default group
     * @param properties  this project's properties
     * @param identity    the node's identity
     * @param authorizer  the profile's authorizer
     * @return the context
     */
    @Bean
    @ConditionalOnMissingBean
    public FleetContext fleetContext(AgentSpaces spaces, AgentSpacesProperties starter,
                                     AgentSpacesSpringAiProperties properties,
                                     PeerIdentity identity, Authorizer authorizer) {
        String group = properties.group();
        if (group.isBlank()) {
            if (starter.getGroups().isEmpty()) {
                throw new IllegalStateException("agentspaces-springai needs a group:"
                        + " configure agentspaces.groups[0] (and optionally agentspaces.springai.group)");
            }
            group = starter.getGroups().get(0).getName();
        }
        return new FleetContext(spaces, group, identity, authorizer);
    }

    /**
     * Applies {@code agentspaces.springai.embedder.require-match} to every
     * group's semantic discovery once the node is built.
     *
     * @param spaces     the node's facade
     * @param properties the properties
     * @return the initializer
     */
    @Bean
    public SmartInitializingSingleton semanticEmbedderMatching(AgentSpaces spaces,
                                                               AgentSpacesSpringAiProperties properties) {
        return () -> {
            for (String group : spaces.groupNames()) {
                spaces.group(group).provider(SemanticDiscovery.TYPE)
                        .filter(SemanticDiscovery.class::isInstance)
                        .map(SemanticDiscovery.class::cast)
                        .ifPresent(semantic -> semantic.requireMatchingEmbedder(properties.embedder().requireMatch()));
            }
        };
    }

    /**
     * The scheduler periodic work runs on.
     *
     * @param scheduler the application's scheduler, if it defines one
     * @return the scheduler holder
     */
    @Bean
    @ConditionalOnMissingBean
    public FleetTaskScheduler fleetTaskScheduler(ObjectProvider<TaskScheduler> scheduler) {
        return new FleetTaskScheduler(scheduler.getIfAvailable());
    }
}
