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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NamingAndFilterTest {

    @Test
    void theDefaultNameIsPrefixedSanitizedAndTruncated() {
        FakeAction action = FakeAction.summarizer();
        assertThat(ToolNamingStrategy.prefixed("fleet_").toolName(action)).isEqualTo("fleet_summarizer_Brief");
        assertThat(ToolNamingStrategy.sanitize("a.b c/d")).isEqualTo("a_b_c_d");
        assertThat(ToolNamingStrategy.sanitize("x".repeat(100))).hasSize(ToolNamingStrategy.MAX_LENGTH);
        assertThat(ToolNamingStrategy.sanitize("")).isEqualTo("fleet_tool");
    }

    @Test
    void theFilterAppliesIncludeExcludeAndAttestation() {
        FakeAction plain = new FakeAction("summarizer", false, in -> Optional.empty());
        FakeAction attested = new FakeAction("translator", true, in -> Optional.empty());

        assertThat(FleetToolFilter.of(List.of(), List.of(), false).include(plain)).isTrue();
        assertThat(FleetToolFilter.of(List.of("translator"), List.of(), false).include(plain)).isFalse();
        assertThat(FleetToolFilter.of(List.of(), List.of("summarizer"), false).include(plain)).isFalse();
        assertThat(FleetToolFilter.of(List.of(), List.of(plain.agent().encoded()), false).include(plain))
                .as("a full AgentId matches too").isFalse();
        assertThat(FleetToolFilter.of(List.of("summarizer"), List.of("summarizer"), false).include(plain))
                .as("exclude wins").isFalse();
        assertThat(FleetToolFilter.of(List.of(), List.of(), true).include(plain)).isFalse();
        assertThat(FleetToolFilter.of(List.of(), List.of(), true).include(attested)).isTrue();
    }
}
