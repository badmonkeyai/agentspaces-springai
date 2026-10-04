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

import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.usage.FleetUsage;
import ai.badmonkey.agentspaces.springai.usage.FleetUsageObservationHandler;
import ai.badmonkey.agentspaces.springai.usage.FleetUsagePublisher;
import ai.badmonkey.agentspaces.springai.usage.UsagePanel;
import ai.badmonkey.agentspaces.springai.usage.UsageSink;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class FleetUsageAutoConfigurationTest {

    @Test
    void offByDefault() {
        FleetApps.runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FleetUsageObservationHandler.class);
        });
    }

    @Test
    void onItCountsPublishesAndShowsAPanel() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.usage.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FleetUsage.class);
            assertThat(context).hasSingleBean(FleetUsageObservationHandler.class);
            assertThat(context.getBean(FleetUsagePublisher.class).isRunning()).isTrue();
            assertThat(context).hasSingleBean(UsagePanel.class);
        });
    }

    @Test
    void anApplicationSinkReplacesTheDefault() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.usage.enabled=true")
                .withUserConfiguration(Sink.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FleetUsage.class);
                    assertThat(context).doesNotHaveBean(UsagePanel.class);
                    assertThat(context).hasSingleBean(FleetUsageObservationHandler.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Sink {
        @Bean
        UsageSink billing() {
            return usage -> { };
        }
    }
}
