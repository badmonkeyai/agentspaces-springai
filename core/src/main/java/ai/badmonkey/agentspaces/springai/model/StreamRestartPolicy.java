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
package ai.badmonkey.agentspaces.springai.model;

import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.RestartMode;

/**
 * What a streaming caller does when its server dies mid-stream. Model
 * generation is not deterministic, so a restarted stream cannot continue where
 * the first one stopped: the caller either fails with the partial text or
 * starts over. A restart before anything was emitted is always silent. Declare
 * a bean of this type for a custom recovery.
 */
@FunctionalInterface
public interface StreamRestartPolicy {

    /** The two outcomes. */
    enum Action {
        /** End the stream with {@link FleetStreamRestartedException}. */
        FAIL,
        /** Emit a restart marker, then the new attempt from its beginning. */
        RESTART
    }

    /**
     * Decides a restart.
     *
     * @param requestId   the request
     * @param fromAttempt the attempt that died
     * @param toAttempt   the attempt now streaming
     * @param partialText the text emitted before the restart
     * @return the action
     */
    Action decide(String requestId, int fromAttempt, int toAttempt, String partialText);

    /**
     * The property-driven default.
     *
     * @param mode the configured mode
     * @return the policy
     */
    static StreamRestartPolicy of(RestartMode mode) {
        Action action = mode == RestartMode.RESTART ? Action.RESTART : Action.FAIL;
        return (requestId, fromAttempt, toAttempt, partialText) -> action;
    }
}
