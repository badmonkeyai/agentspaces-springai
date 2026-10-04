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

/**
 * A model round-trip a caller asks the fleet to perform. A model server takes
 * it (by model name, or by auction) and completes the take with a
 * {@link ModelResponse}.
 *
 * @param requestId the correlation key
 * @param model     the model asked for; empty for the server's default
 * @param messages  the conversation
 * @param options   the portable chat options
 * @param tools     tool definitions the model may call; the caller runs the tools
 * @param stream    whether the caller consumes a stream of {@link ModelChunk}s
 * @param requester the requesting peer, for attribution
 */
public record ModelRequest(String requestId, String model, List<WireMessage> messages,
                           WireOptions options, List<WireToolDefinition> tools,
                           boolean stream, String requester) {

    /** Normalizes absent lists. */
    public ModelRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        model = model == null ? "" : model;
    }
}
