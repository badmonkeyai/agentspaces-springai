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
package ai.badmonkey.agentspaces.springai.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link FleetCapabilityTools} without a fleet: the switches, the schemas, and the error path. */
class FleetCapabilityToolsTest {

    private static FleetCapabilityTools unprovided(Set<String> enabled) {
        return new FleetCapabilityTools(
                () -> { throw new IllegalStateException("no vote capability resolves in group 'g'"); },
                () -> { throw new IllegalStateException("group 'g' is not networked"); },
                Duration.ofHours(1), Duration.ofSeconds(1), new JsonMapper(), enabled);
    }

    @Test
    void eachFamilySwitchesOnSeparately() {
        assertThat(names(unprovided(FleetCapabilityTools.enabled(true, false))))
                .containsExactlyInAnyOrder("propose_vote", "cast_ballot", "read_tally", "read_decision");
        assertThat(names(unprovided(FleetCapabilityTools.enabled(false, true))))
                .containsExactlyInAnyOrder("contribute", "read_estimate");
        assertThat(names(unprovided(FleetCapabilityTools.enabled(true, true)))).hasSize(6);
        assertThat(names(unprovided(FleetCapabilityTools.enabled(false, false)))).isEmpty();
        assertThat(unprovided(FleetCapabilityTools.enabled(true, false)).enabled("contribute")).isFalse();
    }

    @Test
    void theSchemasNameEveryParameter() {
        ToolCallback[] callbacks = unprovided(FleetCapabilityTools.enabled(true, true)).toolCallbacks()
                .getToolCallbacks();
        ToolCallback propose = Arrays.stream(callbacks)
                .filter(c -> c.getToolDefinition().name().equals("propose_vote")).findFirst().orElseThrow();
        assertThat(propose.getToolDefinition().inputSchema()).contains("\"id\"", "\"question\"", "\"options\"",
                "\"quorum\"");
        ToolCallback contribute = Arrays.stream(callbacks)
                .filter(c -> c.getToolDefinition().name().equals("contribute")).findFirst().orElseThrow();
        assertThat(contribute.getToolDefinition().inputSchema()).contains("\"epoch\"", "\"value\"");
    }

    @Test
    void anUnprovidedCapabilityAnswersWithAnErrorTheModelCanRead() {
        FleetCapabilityTools tools = unprovided(FleetCapabilityTools.enabled(true, true));
        assertThat(tools.proposeVote("p", "q?", List.of("a", "b"), 1))
                .contains("\"error\"", "vote capability is not provided", "no vote capability resolves");
        assertThat(tools.castBallot("p", "a")).contains("\"error\"");
        assertThat(tools.readTally("p")).contains("\"error\"");
        assertThat(tools.readDecision("p")).contains("\"error\"");
        assertThat(tools.contribute("e", 1.0))
                .contains("\"error\"", "aggregate capability is not provided", "not networked");
        assertThat(tools.readEstimate("e")).contains("\"error\"");
    }

    @Test
    void theErrorReachesTheModelThroughTheCallbackRatherThanFailingTheToolLoop() {
        ToolCallback propose = Arrays.stream(unprovided(FleetCapabilityTools.enabled(true, false))
                        .toolCallbacks().getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals("propose_vote")).findFirst().orElseThrow();
        String result = propose.call("{\"id\":\"p\",\"question\":\"q?\",\"options\":[\"a\",\"b\"],\"quorum\":1}");
        assertThat(result).contains("\"error\"");
    }

    private static List<String> names(FleetCapabilityTools tools) {
        return Arrays.stream(tools.toolCallbacks().getToolCallbacks())
                .map(c -> c.getToolDefinition().name()).toList();
    }
}
