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

import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.OnTimeout;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.execution.ToolExecutionException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteActionToolCallbackTest {

    private final JsonMapper json = new JsonMapper();

    private RemoteActionToolCallback callback(FakeAction action, OnTimeout onTimeout,
                                              ObservationRegistry observations) {
        return new RemoteActionToolCallback(action, "fleet_summarizer_Brief", json,
                Duration.ofSeconds(5), onTimeout, observations);
    }

    @Test
    void theDefinitionDescribesTheCardAndTheInputRecord() {
        RemoteActionToolCallback tool = callback(FakeAction.summarizer(), OnTimeout.RESULT,
                ObservationRegistry.NOOP);
        assertThat(tool.getToolDefinition().name()).isEqualTo("fleet_summarizer_Brief");
        assertThat(tool.getToolDefinition().description())
                .contains("Summarizes briefs.", "Goals: summarize", "Takes a Brief and returns a Summary",
                        "an agent on another peer that takes Brief (advertised by 'summarizer') performs it");
        assertThat(tool.getToolDefinition().inputSchema()).contains("\"topic\"", "\"words\"");
    }

    @Test
    void aCallBecomesTheInputRecordAndTheResultComesBackAsJson() {
        FakeAction action = FakeAction.summarizer();
        String result = callback(action, OnTimeout.RESULT, ObservationRegistry.NOOP)
                .call("{\"topic\":\"tuple spaces\",\"words\":50}");
        assertThat(action.inputs).containsExactly(new FakeAction.Brief("tuple spaces", 50));
        assertThat(result).contains("\"topic\":\"tuple spaces\"", "\"text\":\"summary of tuple spaces\"");
    }

    @Test
    void unknownAndMissingFieldsAreRefusedBeforeAnythingIsWritten() {
        FakeAction action = FakeAction.summarizer();
        RemoteActionToolCallback tool = callback(action, OnTimeout.RESULT, ObservationRegistry.NOOP);
        assertThatThrownBy(() -> tool.call("{\"topic\":\"x\",\"words\":1,\"extra\":true}"))
                .isInstanceOf(ToolExecutionException.class);
        assertThatThrownBy(() -> tool.call("{\"topic\":\"x\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("words");
        assertThatThrownBy(() -> tool.call("[1,2]")).isInstanceOf(ToolExecutionException.class);
        assertThat(action.inputs).isEmpty();
    }

    @Test
    void aTimeoutReturnsAnExplanatoryResultOrThrowsAsConfigured() {
        FakeAction silent = new FakeAction("summarizer", false, in -> Optional.empty());
        assertThat(callback(silent, OnTimeout.RESULT, ObservationRegistry.NOOP)
                .call("{\"topic\":\"x\",\"words\":1}"))
                .contains("\"error\"", "the fleet produced no Summary for this Brief within PT5S");
        assertThatThrownBy(() -> callback(silent, OnTimeout.THROW, ObservationRegistry.NOOP)
                .call("{\"topic\":\"x\",\"words\":1}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasRootCauseMessage("the fleet produced no Summary for this Brief within PT5S");
    }

    @Test
    void aFailingInvocationSurfacesAsAToolExecutionException() {
        FakeAction broken = new FakeAction("summarizer", false, in -> {
            throw new IllegalStateException("space closed");
        });
        assertThatThrownBy(() -> callback(broken, OnTimeout.RESULT, ObservationRegistry.NOOP)
                .call("{\"topic\":\"x\",\"words\":1}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasRootCauseMessage("space closed");
    }

    @Test
    void everyCallIsObserved() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        callback(FakeAction.summarizer(), OnTimeout.RESULT, registry)
                .call("{\"topic\":\"x\",\"words\":1}");
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo(RemoteActionToolCallback.OBSERVATION)
                .that()
                .hasLowCardinalityKeyValue("agentspaces.tool.name", "fleet_summarizer_Brief")
                .hasLowCardinalityKeyValue("agentspaces.agent", "summarizer")
                .hasBeenStopped();
    }
}
