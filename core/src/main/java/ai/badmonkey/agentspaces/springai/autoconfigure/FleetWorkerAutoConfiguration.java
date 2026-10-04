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
import ai.badmonkey.agentspaces.springai.worker.TakeContextAccessor;
import ai.badmonkey.agentspaces.springai.worker.TakeLeaseAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * F3, crash-safe LLM workers: registers the {@link TakeContextAccessor} so the
 * worker's take crosses Reactor threads, and adds a {@link TakeLeaseAdvisor} to
 * every auto-configured {@code ChatClient.Builder} through a
 * {@link ChatClientBuilderCustomizer}, so a model call inside a
 * {@code @SpaceTake} method keeps its claim for as long as it makes progress.
 */
@AutoConfiguration(after = AgentSpacesSpringAiAutoConfiguration.class)
@ConditionalOnClass(ChatClient.class)
@ConditionalOnBean(FleetContext.class)
public class FleetWorkerAutoConfiguration {

    /**
     * Carries the worker's take across threads.
     *
     * @return the accessor, registered with Micrometer's context registry
     */
    @Bean
    @ConditionalOnMissingBean
    public TakeContextAccessor takeContextAccessor() {
        return new TakeContextAccessor();
    }

    /** The take-lease advisor and the customizer that adds it; on unless disabled. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "agentspaces.springai.take-lease", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class TakeLease {

        @Bean
        @ConditionalOnMissingBean
        TakeLeaseAdvisor takeLeaseAdvisor(FleetTaskScheduler scheduler,
                                          AgentSpacesSpringAiProperties properties) {
            return new TakeLeaseAdvisor(scheduler.scheduler(),
                    properties.takeLease().streamRenewInterval());
        }

        @Bean
        ChatClientBuilderCustomizer takeLeaseChatClientCustomizer(TakeLeaseAdvisor advisor) {
            return builder -> builder.defaultAdvisors(advisor);
        }
    }
}
