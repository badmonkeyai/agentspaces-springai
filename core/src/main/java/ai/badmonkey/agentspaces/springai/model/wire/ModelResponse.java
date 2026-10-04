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
 * A model server's answer, written atomically with the completion of the
 * request's take.
 *
 * @param requestId   the request it answers
 * @param model       the model that answered
 * @param generations the generations
 * @param usage       token usage
 * @param error       the provider's error, when the call failed
 * @param servedBy    the serving peer
 * @param attempt     which attempt produced it (1 for the first server, higher after a crash)
 */
public record ModelResponse(String requestId, String model, List<WireGeneration> generations,
                            WireUsage usage, String error, String servedBy, int attempt) {

    /** Normalizes an absent list. */
    public ModelResponse {
        generations = generations == null ? List.of() : List.copyOf(generations);
    }
}
