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

import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.springai.FleetContext;
import ai.badmonkey.agentspaces.springai.FleetTaskScheduler;
import ai.badmonkey.agentspaces.springai.usage.FleetUsage;
import ai.badmonkey.agentspaces.springai.usage.FleetUsageObservationHandler;
import ai.badmonkey.agentspaces.springai.usage.FleetUsagePublisher;
import ai.badmonkey.agentspaces.springai.usage.UsagePanel;
import ai.badmonkey.agentspaces.springai.usage.UsageSink;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * F6: fleet-wide token usage. An {@code ObservationHandler} bean (which Spring
 * Boot adds to the {@code ObservationRegistry}) feeds the {@link UsageSink}; the
 * default sink, {@link FleetUsage}, keeps local counts, joins push-sum SUM
 * epochs on an interval, and optionally writes attributed usage entries; and
 * {@link UsagePanel} shows it all in the fleet console.
 */
@AutoConfiguration(after = AgentSpacesSpringAiAutoConfiguration.class)
@ConditionalOnClass(ChatModelObservationContext.class)
@ConditionalOnBean(FleetContext.class)
@ConditionalOnProperty(prefix = "agentspaces.springai.usage", name = "enabled", havingValue = "true")
public class FleetUsageAutoConfiguration {

    /**
     * The default sink.
     *
     * @param fleet      the fleet context
     * @param properties the properties
     * @return the sink
     */
    @Bean
    @ConditionalOnMissingBean(UsageSink.class)
    public FleetUsage fleetUsage(FleetContext fleet, AgentSpacesSpringAiProperties properties) {
        AgentSpacesSpringAiProperties.Usage usage = properties.usage();
        PushSumAggregate aggregate = fleet.group().provider(PushSumAggregate.TYPE)
                .filter(PushSumAggregate.class::isInstance).map(PushSumAggregate.class::cast).orElse(null);
        return new FleetUsage(aggregate,
                usage.metricsSpace().isBlank() ? null
                        : fleet.requiredSpace(usage.metricsSpace(), "agentspaces.springai.usage.metrics-space"),
                usage.entryLease(), usage.publishInterval(), Clock.systemUTC());
    }

    /**
     * Feeds Spring AI's model observations to the sink.
     *
     * @param sink  the application's sink, or the default
     * @param fleet the fleet context
     * @return the handler
     */
    @Bean
    @ConditionalOnMissingBean
    public FleetUsageObservationHandler fleetUsageObservationHandler(UsageSink sink, FleetContext fleet) {
        return new FleetUsageObservationHandler(sink, fleet.peerId().value());
    }

    /**
     * Publishes local totals into the fleet-wide sums.
     *
     * @param usage      the default sink
     * @param scheduler  the scheduler
     * @param properties the properties
     * @return the publisher
     */
    @Bean
    @ConditionalOnBean(FleetUsage.class)
    @ConditionalOnMissingBean
    public FleetUsagePublisher fleetUsagePublisher(FleetUsage usage, FleetTaskScheduler scheduler,
                                                   AgentSpacesSpringAiProperties properties) {
        return new FleetUsagePublisher(usage, scheduler.scheduler(), properties.usage().publishInterval());
    }

    /**
     * The console panel.
     *
     * @param usage the default sink
     * @return the panel
     */
    @Bean
    @ConditionalOnBean(FleetUsage.class)
    @ConditionalOnMissingBean
    public UsagePanel usagePanel(FleetUsage usage) {
        return new UsagePanel(usage);
    }
}
