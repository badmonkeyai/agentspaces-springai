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

/**
 * A streamed fleet model call whose server died mid-stream, under the
 * {@code fail} restart policy. The partial text is what the caller already
 * received; the caller can retry, or keep it.
 */
public class FleetStreamRestartedException extends FleetModelException {

    private final String partialText;
    private final int fromAttempt;
    private final int toAttempt;

    /**
     * Creates the exception.
     *
     * @param requestId   the request
     * @param fromAttempt the attempt that died
     * @param toAttempt   the attempt that took over
     * @param partialText the text emitted before the restart
     */
    public FleetStreamRestartedException(String requestId, int fromAttempt, int toAttempt, String partialText) {
        super("model stream " + requestId + " restarted: attempt " + fromAttempt
                + " died and attempt " + toAttempt + " took over");
        this.partialText = partialText;
        this.fromAttempt = fromAttempt;
        this.toAttempt = toAttempt;
    }

    /** The text emitted before the restart. */
    public String partialText() {
        return partialText;
    }

    /** The attempt that died. */
    public int fromAttempt() {
        return fromAttempt;
    }

    /** The attempt that took over. */
    public int toAttempt() {
        return toAttempt;
    }
}
