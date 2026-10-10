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
import ai.badmonkey.agentspaces.springai.tools.FleetCapabilityTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FleetCapabilityToolsAutoConfigurationTest {

    @Test
    void offByDefault() {
        FleetApps.runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FleetCapabilityTools.class);
            assertThat(context.getBean(AgentSpacesSpringAiProperties.class).capabilityTools().any()).isFalse();
        });
    }

    @Test
    void eachFamilyIsEnabledSeparately() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.capability-tools.vote=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("never a ToolCallbackProvider bean: the MCP server would publish it")
                            .doesNotHaveBean(ToolCallbackProvider.class);
                    assertThat(context.getBean(FleetCapabilityTools.class).toolCallbacks().getToolCallbacks())
                            .extracting(c -> c.getToolDefinition().name())
                            .containsExactlyInAnyOrder("propose_vote", "cast_ballot", "read_tally", "read_decision");
                });
        FleetApps.runner().withPropertyValues("agentspaces.springai.capability-tools.aggregate=true")
                .run(context -> assertThat(context.getBean(FleetCapabilityTools.class)
                        .toolCallbacks().getToolCallbacks())
                        .extracting(c -> c.getToolDefinition().name())
                        .containsExactlyInAnyOrder("contribute", "read_estimate"));
    }

    @Test
    void propertiesBindAndInvalidValuesFailAtStartupNamingTheProperty() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.capability-tools.vote=true",
                        "agentspaces.springai.capability-tools.ballot-lease=2h",
                        "agentspaces.springai.capability-tools.settle-timeout=3s")
                .run(context -> {
                    AgentSpacesSpringAiProperties.CapabilityTools config =
                            context.getBean(AgentSpacesSpringAiProperties.class).capabilityTools();
                    assertThat(config.ballotLease()).isEqualTo(Duration.ofHours(2));
                    assertThat(config.settleTimeout()).isEqualTo(Duration.ofSeconds(3));
                });
        FleetApps.runner().withPropertyValues("agentspaces.springai.capability-tools.settle-timeout=0s")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("agentspaces.springai.capability-tools.settle-timeout"));
    }

    @Test
    void theToolsResolveTheStartersVoteAndAggregateWhenFirstCalled() throws Exception {
        // The starter provides both capabilities by default (a private votes space
        // when none is declared), so a one-node fleet can open and read a vote
        // and start an epoch; the clients resolve on first use rather than at startup.
        FleetApps.runner().withPropertyValues("agentspaces.springai.capability-tools.vote=true",
                        "agentspaces.springai.capability-tools.aggregate=true",
                        "agentspaces.springai.capability-tools.settle-timeout=200ms")
                .run(context -> {
                    FleetCapabilityTools tools = context.getBean(FleetCapabilityTools.class);
                    assertThat(tools.proposeVote("p", "q?", java.util.List.of("a", "b"), 1))
                            .contains("\"proposalId\":\"p\"", "\"quorum\":1");
                    assertThat(tools.castBallot("p", "a")).contains("\"cast\":\"a\"");
                    assertThat(tools.readDecision("p")).contains("\"winner\":\"a\"");
                    assertThat(tools.contribute("e", 4.0)).contains("\"contributed\":4.0");
                    // The estimate becomes known on the next protocol tick, so the read
                    // polls a few ticks rather than racing the first one.
                    String estimate = tools.readEstimate("e");
                    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (!estimate.contains("\"estimate\":4.0") && System.nanoTime() < deadline) {
                        Thread.sleep(100);
                        estimate = tools.readEstimate("e");
                    }
                    assertThat(estimate).contains("\"estimate\":4.0");
                });
    }

    @Test
    void withTheVoteCapabilityOffTheVoteToolsAnswerWithAnError() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.capability-tools.vote=true",
                        "agentspaces.capabilities.vote=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FleetCapabilityTools.class).readTally("p"))
                            .contains("\"error\"", "vote capability is not provided");
                });
    }
}
