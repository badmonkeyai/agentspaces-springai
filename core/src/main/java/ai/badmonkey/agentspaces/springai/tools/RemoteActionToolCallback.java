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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One fleet action as a Spring AI tool. The model's JSON arguments become the
 * action's input record (unknown and missing fields are refused before anything
 * is written to a space), the record is written to the fleet as a task, and the
 * correlated result record comes back to the model as JSON. As everywhere in a
 * tuple space, the task goes to whichever agent takes its type: the card names
 * one such agent, and space admission ({@code SPACE_TAKE}) decides who may. Each call is an
 * {@code agentspaces.fleet.tool} observation, so the fleet hop appears in traces
 * beside Spring AI's own spans.
 */
public class RemoteActionToolCallback implements ToolCallback {

    /** The observation every fleet tool call records. */
    public static final String OBSERVATION = "agentspaces.fleet.tool";

    private final FleetAction action;
    private final ToolDefinition definition;
    private final JsonMapper json;
    private final Duration timeout;
    private final OnTimeout onTimeout;
    private final ObservationRegistry observations;

    /**
     * Creates the callback.
     *
     * @param action       the fleet action
     * @param toolName     the tool's unique name
     * @param json         the JSON mapper
     * @param timeout      how long one call waits for its result
     * @param onTimeout    what a timeout returns
     * @param observations the registry fleet calls are observed on
     */
    public RemoteActionToolCallback(FleetAction action, String toolName, JsonMapper json,
                                    Duration timeout, OnTimeout onTimeout,
                                    ObservationRegistry observations) {
        this.action = Objects.requireNonNull(action, "action");
        this.json = Objects.requireNonNull(json, "json").rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.onTimeout = Objects.requireNonNull(onTimeout, "onTimeout");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.definition = ToolDefinition.builder()
                .name(Objects.requireNonNull(toolName, "toolName"))
                .description(describe(action))
                .inputSchema(JsonSchemaGenerator.generateForType(action.inputType()))
                .build();
    }

    /** The fleet action behind this tool. */
    public FleetAction action() {
        return action;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Object input = parse(toolInput);
        Observation observation = Observation.createNotStarted(OBSERVATION, observations)
                .lowCardinalityKeyValue("agentspaces.tool.name", definition.name())
                .lowCardinalityKeyValue("agentspaces.agent", action.agent().localName())
                .contextualName("fleet tool " + definition.name());
        return observation.observe(() -> invoke(input));
    }

    private String invoke(Object input) {
        Optional<Object> result;
        try {
            result = action.invoke(input, timeout);
        } catch (RuntimeException e) {
            throw new ToolExecutionException(definition, e);
        }
        if (result.isPresent()) {
            return json.writeValueAsString(result.get());
        }
        String message = "the fleet produced no " + action.outputType().getSimpleName()
                + " for this " + action.inputType().getSimpleName() + " within " + timeout;
        if (onTimeout == OnTimeout.THROW) {
            throw new ToolExecutionException(definition, new IllegalStateException(message));
        }
        return json.writeValueAsString(Map.of("error", message));
    }

    private Object parse(String toolInput) {
        try {
            JsonNode node = json.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            if (action.inputType().isRecord()) {
                if (!node.isObject()) {
                    throw new IllegalArgumentException("expected a JSON object for "
                            + action.inputType().getSimpleName());
                }
                List<String> missing = new ArrayList<>();
                for (RecordComponent component : action.inputType().getRecordComponents()) {
                    if (!node.has(component.getName())) {
                        missing.add(component.getName());
                    }
                }
                if (!missing.isEmpty()) {
                    throw new IllegalArgumentException("missing field(s) " + missing + " for "
                            + action.inputType().getSimpleName());
                }
            }
            return json.treeToValue(node, action.inputType());
        } catch (JacksonException | IllegalArgumentException e) {
            throw new ToolExecutionException(definition, e);
        }
    }

    private static String describe(FleetAction action) {
        StringBuilder text = new StringBuilder();
        String description = action.description();
        if (description != null && !description.isBlank()) {
            text.append(description.strip());
            if (!description.strip().endsWith(".")) {
                text.append('.');
            }
            text.append(' ');
        }
        if (!action.goals().isEmpty()) {
            text.append("Goals: ").append(String.join("; ", action.goals())).append(". ");
        }
        text.append("Takes a ").append(action.inputType().getSimpleName())
                .append(" and returns a ").append(action.outputType().getSimpleName())
                .append(". The fleet routes the task by type: an agent on another peer that takes ")
                .append(action.inputType().getSimpleName()).append(" (advertised by '")
                .append(action.agent().localName()).append("') performs it.");
        return text.toString();
    }

    @Override
    public String toString() {
        return "RemoteActionToolCallback[" + definition.name() + " -> " + action + "]";
    }
}
