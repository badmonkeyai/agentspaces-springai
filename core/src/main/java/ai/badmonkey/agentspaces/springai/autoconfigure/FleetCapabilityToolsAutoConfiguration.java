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
import ai.badmonkey.agentspaces.springai.tools.FleetCapabilityTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import ai.badmonkey.agentspaces.spring.Pdts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * F9: tools that let a model take part in the fleet's capabilities:
 * {@code propose_vote}, {@code cast_ballot}, {@code read_tally}, and
 * {@code read_decision} over the group's vote; {@code contribute} and
 * {@code read_estimate} over its push-sum aggregate. Each family is enabled
 * by its own property, and the clients resolve from the group when a tool
 * first needs them, so a peer that provides no vote still starts.
 */
@AutoConfiguration(after = AgentSpacesSpringAiAutoConfiguration.class)
@ConditionalOnClass(ToolCallbackProvider.class)
@ConditionalOnBean(FleetContext.class)
@ConditionalOnExpression("${agentspaces.springai.capability-tools.vote:false}"
        + " or ${agentspaces.springai.capability-tools.aggregate:false}"
        + " or ${agentspaces.springai.capability-tools.content:false}"
        + " or ${agentspaces.springai.capability-tools.pdt:false}")
public class FleetCapabilityToolsAutoConfiguration {

    /**
     * The capability tools.
     *
     * @param fleet      the fleet context
     * @param properties the properties
     * @param json       the application's JSON mapper, if any
     * @param pdts       the starter's persistent-tree holder, present when {@code agentspaces.pdt.enabled}
     *                   built one (srad/agentspaces-pdt.md PD-C-02); the pdt tools resolve the fleet
     *                   group's facade from it when first used
     * @return the tools
     */
    @Bean
    @ConditionalOnMissingBean
    public FleetCapabilityTools fleetCapabilityTools(FleetContext fleet,
                                                     AgentSpacesSpringAiProperties properties,
                                                     ObjectProvider<JsonMapper> json,
                                                     ObjectProvider<Pdts> pdts) {
        AgentSpacesSpringAiProperties.CapabilityTools config = properties.capabilityTools();
        return new FleetCapabilityTools(fleet, config.vote(), config.aggregate(), config.content(), config.pdt(),
                config.ballotLease(), config.settleTimeout(), config.fetchTimeout(),
                json.getIfAvailable(JsonMapper::new),
                () -> {
                    Pdts holder = pdts.getIfAvailable();
                    if (holder == null) {
                        throw new IllegalStateException("the persistent trees are not enabled (agentspaces.pdt.enabled=true,"
                                + " with agentspaces.content.enabled=true)");
                    }
                    return holder.forGroup(fleet.groupName());
                });
    }
}
