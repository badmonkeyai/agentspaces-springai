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

import ai.badmonkey.agentspaces.springai.FleetContext;
import ai.badmonkey.agentspaces.springai.FleetTaskScheduler;
import ai.badmonkey.agentspaces.springai.model.FleetChatModel;
import ai.badmonkey.agentspaces.springai.model.StreamRestartPolicy;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;

/**
 * F4, calling side: the {@code fleetChatModel} bean. It is a
 * {@link Fallback @Fallback} bean: on a node with no model provider it is the
 * application's {@code ChatModel}, so the auto-configured {@code ChatClient}
 * calls the fleet; beside a provider {@code ChatModel}, the provider stays the
 * default and the fleet model is injected by name ({@code @Qualifier("fleetChatModel")}).
 * Ordered before Spring AI's ChatClient auto-configuration, which needs the
 * {@code ChatModel} to exist.
 */
@AutoConfiguration(after = AgentSpacesSpringAiAutoConfiguration.class,
        beforeName = "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration")
@ConditionalOnClass(ChatModel.class)
@ConditionalOnBean(FleetContext.class)
@ConditionalOnProperty(prefix = "agentspaces.springai.model-client", name = "enabled", havingValue = "true")
public class FleetModelClientAutoConfiguration {

    /**
     * What a stream does when its server dies mid-stream.
     *
     * @param properties the properties
     * @return the policy
     */
    @Bean
    @ConditionalOnMissingBean
    public StreamRestartPolicy streamRestartPolicy(AgentSpacesSpringAiProperties properties) {
        return StreamRestartPolicy.of(properties.modelClient().stream().onRestart());
    }

    /**
     * The fleet as a Spring AI chat model.
     *
     * @param fleet        the fleet context
     * @param properties   the properties
     * @param restart      the restart policy
     * @param scheduler    the scheduler
     * @param observations the observation registry, if any
     * @return the model
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean(FleetChatModel.class)
    public FleetChatModel fleetChatModel(FleetContext fleet, AgentSpacesSpringAiProperties properties,
                                         StreamRestartPolicy restart, FleetTaskScheduler scheduler,
                                         ObjectProvider<ObservationRegistry> observations) {
        AgentSpacesSpringAiProperties.ModelClient client = properties.modelClient();
        return new FleetChatModel(fleet.requiredSpace(client.space(), "agentspaces.springai.model-client.space"),
                fleet.authorizer(), fleet.peerId().value(), client.model(), client.timeout(), restart,
                scheduler.scheduler(), observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}
