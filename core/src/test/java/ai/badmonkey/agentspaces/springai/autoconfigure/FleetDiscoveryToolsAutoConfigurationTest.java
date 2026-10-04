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
import ai.badmonkey.agentspaces.springai.tools.FleetDiscoveryTools;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FleetDiscoveryToolsAutoConfigurationTest {

    @Test
    void offByDefault() {
        FleetApps.runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FleetDiscoveryTools.class);
        });
    }

    @Test
    void eachToolIsEnabledSeparately() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.discovery-tools.agents=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FleetDiscoveryTools.class).toolCallbacks().getToolCallbacks())
                            .extracting(c -> c.getToolDefinition().name())
                            .containsExactly("find_fleet_agents");
                });
        FleetApps.runner("work", "data").withPropertyValues(
                        "agentspaces.springai.discovery-tools.assets=true",
                        "agentspaces.springai.discovery-tools.data=true")
                .run(context -> assertThat(context.getBean(FleetDiscoveryTools.class)
                        .toolCallbacks().getToolCallbacks())
                        .extracting(c -> c.getToolDefinition().name())
                        .containsExactlyInAnyOrder("list_fleet_assets", "fetch_fleet_data"));
    }

    @Test
    void theDataToolWithoutItsSpaceFailsWithTheYamlThatAddsIt() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.discovery-tools.data=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("agentspaces.springai.discovery-tools.data-space")
                        .hasMessageContaining("- name: data"));
    }
}
