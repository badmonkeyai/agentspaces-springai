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
package ai.badmonkey.agentspaces.springai.model.wire;

import java.util.List;
import java.util.Map;

/**
 * One chat message.
 *
 * @param role          {@code user}, {@code system}, {@code assistant}, or {@code tool}
 * @param text          the text, for every role but {@code tool}
 * @param toolCalls     the tool calls an assistant message asks for
 * @param toolResponses the tool results a tool message carries
 * @param media         media attached to a user or assistant message
 * @param metadata      string-valued message metadata
 */
public record WireMessage(String role, String text, List<WireToolCall> toolCalls,
                          List<WireToolResponse> toolResponses, List<WireMedia> media,
                          Map<String, String> metadata) {

    /** Normalizes absent lists and maps to empty ones. */
    public WireMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolResponses = toolResponses == null ? List.of() : List.copyOf(toolResponses);
        media = media == null ? List.of() : List.copyOf(media);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
