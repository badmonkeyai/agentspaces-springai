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

import ai.badmonkey.agentspaces.springai.mcp.FleetMcpToolSync;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FleetMcpAutoConfigurationTest {

    @Test
    void withoutAnMcpServerTheExportStaysOffEvenWhenEnabled() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.mcp.enabled=true",
                        "agentspaces.springai.mcp.include-agents=summarizer")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FleetMcpToolSync.class);
                });
    }

    @Test
    void offByDefault() {
        FleetApps.runner().run(context -> assertThat(context).doesNotHaveBean(FleetMcpToolSync.class));
    }

    @Test
    void theDiscoveryAndCapabilityToolsExportOnlyWhenNamed() {
        McpSyncServer server = mock(McpSyncServer.class);
        FleetApps.runner().withBean(McpSyncServer.class, () -> server)
                .withPropertyValues("agentspaces.springai.mcp.enabled=true",
                        "agentspaces.springai.discovery-tools.agents=true",
                        "agentspaces.springai.capability-tools.vote=true",
                        "agentspaces.springai.capability-tools.aggregate=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FleetMcpToolSync.class).published())
                            .as("tools enabled in-process are not exported until the mcp flags say so")
                            .isEmpty();
                });
        McpSyncServer exporting = mock(McpSyncServer.class);
        FleetApps.runner().withBean(McpSyncServer.class, () -> exporting)
                .withPropertyValues("agentspaces.springai.mcp.enabled=true",
                        "agentspaces.springai.mcp.discovery-tools=true",
                        "agentspaces.springai.mcp.capability-tools=true",
                        "agentspaces.springai.discovery-tools.agents=true",
                        "agentspaces.springai.capability-tools.vote=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FleetMcpToolSync.class).published())
                            .containsExactlyInAnyOrder("find_fleet_agents", "propose_vote", "cast_ballot",
                                    "read_tally", "read_decision");
                    ArgumentCaptor<McpServerFeatures.SyncToolSpecification> specs =
                            ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
                    verify(exporting, atLeast(5)).addTool(specs.capture());
                    assertThat(specs.getAllValues()).extracting(spec -> spec.tool().name())
                            .contains("propose_vote", "find_fleet_agents");
                });
    }
}
