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
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.tools.FleetTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import ai.badmonkey.agentspaces.springai.tools.FleetToolFilter;
import ai.badmonkey.agentspaces.springai.tools.FleetToolsRefresher;
import ai.badmonkey.agentspaces.springai.tools.ToolNamingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class FleetToolsAutoConfigurationTest {

    @Test
    void theToolsFeatureIsOnByDefault() {
        FleetApps.runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FleetContext.class);
            assertThat(context).hasSingleBean(FleetTaskScheduler.class);
            assertThat(context).hasSingleBean(FleetTools.class);
            assertThat(context).hasSingleBean(FleetToolsRefresher.class);
            assertThat(context).as("never a ToolCallbackProvider bean: the MCP server would publish it")
                    .doesNotHaveBean(ToolCallbackProvider.class);
            assertThat(context.getBean(FleetContext.class).groupName()).isEqualTo("test-fleet");
            assertThat(context.getBean(FleetToolsRefresher.class).isRunning()).isTrue();
            assertThat(context.getBean(AgentSpacesSpringAiProperties.class).tools().prefix())
                    .isEqualTo("fleet_");
        });
    }

    @Test
    void thePropertySwitchesTheFeatureOff() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.tools.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FleetContext.class);
                    assertThat(context).doesNotHaveBean(FleetTools.class);
                });
    }

    @Test
    void withoutSpringAiEveryFeatureBacksOff() {
        FleetApps.runner().withClassLoader(new FilteredClassLoader(ChatModel.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FleetContext.class);
                    assertThat(context).doesNotHaveBean(FleetTools.class);
                });
    }

    @Test
    void anApplicationBeanOfEachExtensionPointReplacesTheDefault() {
        FleetApps.runner().withUserConfiguration(Overrides.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ToolNamingStrategy.class)).isSameAs(Overrides.NAMING);
            assertThat(context.getBean(FleetToolFilter.class)).isSameAs(Overrides.FILTER);
        });
    }

    @Test
    void propertiesBindAndInvalidValuesFailAtStartupNamingTheProperty() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.tools.prefix=ops_",
                        "agentspaces.springai.tools.timeout=45s",
                        "agentspaces.springai.tools.on-timeout=throw",
                        "agentspaces.springai.tools.include-agents=summarizer,translator")
                .run(context -> {
                    AgentSpacesSpringAiProperties.Tools tools =
                            context.getBean(AgentSpacesSpringAiProperties.class).tools();
                    assertThat(tools.prefix()).isEqualTo("ops_");
                    assertThat(tools.timeout()).hasSeconds(45);
                    assertThat(tools.onTimeout()).isEqualTo(AgentSpacesSpringAiProperties.OnTimeout.THROW);
                    assertThat(tools.includeAgents()).containsExactly("summarizer", "translator");
                });
        FleetApps.runner().withPropertyValues("agentspaces.springai.tools.prefix=bad prefix")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("agentspaces.springai.tools.prefix"));
    }

    @Test
    void anUnknownGroupFailsWithTheJoinedGroups() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.group=nope")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("nope")
                        .hasMessageContaining("test-fleet"));
    }

    @Configuration(proxyBeanMethods = false)
    static class Overrides {
        static final ToolNamingStrategy NAMING = action -> "custom_" + action.name();
        static final FleetToolFilter FILTER = action -> false;

        @Bean
        ToolNamingStrategy namingStrategy() {
            return NAMING;
        }

        @Bean
        FleetToolFilter toolFilter() {
            return FILTER;
        }
    }
}
