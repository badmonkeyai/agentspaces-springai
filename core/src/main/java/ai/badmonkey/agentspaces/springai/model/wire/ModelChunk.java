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
 * A batch of streamed deltas. Chunks are short-leased, so they collect
 * themselves; the full answer also lands as the request's {@link ModelResponse}.
 *
 * @param requestId the request it streams
 * @param attempt   which attempt produced it; a higher attempt means the first server died
 * @param seq       the chunk's position in its attempt, from 0
 * @param deltas    the deltas, in order
 * @param last      whether this chunk ends the attempt's stream
 * @param usage     token usage, on the last chunk
 * @param error     the provider's error, on a last chunk that failed
 * @param servedBy  the serving peer
 */
public record ModelChunk(String requestId, int attempt, int seq, List<WireGeneration> deltas,
                         boolean last, WireUsage usage, String error, String servedBy) {

    /** Normalizes an absent list. */
    public ModelChunk {
        deltas = deltas == null ? List.of() : List.copyOf(deltas);
    }
}
