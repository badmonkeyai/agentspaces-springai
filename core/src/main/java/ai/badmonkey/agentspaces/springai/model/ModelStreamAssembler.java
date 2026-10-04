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

import ai.badmonkey.agentspaces.springai.model.wire.ModelChunk;
import ai.badmonkey.agentspaces.springai.model.wire.WireGeneration;

import java.util.Objects;
import java.util.TreeMap;

/**
 * Turns the chunk entries of one streamed request, which gossip may deliver
 * late, twice, or out of order, into one ordered stream. Chunks of the current
 * attempt are emitted strictly by sequence; duplicates are dropped; a gap waits
 * until the chunk arrives (the caller's read-through fills what notifications
 * miss). A chunk of a higher attempt means the first server died: before
 * anything was emitted the switch is silent, and after it the
 * {@link StreamRestartPolicy} decides between failing and restarting.
 */
public final class ModelStreamAssembler {

    /** Receives the assembled stream. */
    public interface Listener {

        /** The next chunk, in order. */
        void onChunk(ModelChunk chunk);

        /** A restart under the {@code restart} policy: the new attempt follows from its start. */
        void onRestart(int fromAttempt, int toAttempt);

        /** The stream ended normally; the last chunk was already delivered. */
        void onComplete(ModelChunk last);

        /** The stream failed. */
        void onError(Throwable error);
    }

    private final String requestId;
    private final StreamRestartPolicy policy;
    private final Listener listener;
    private final TreeMap<Integer, ModelChunk> pending = new TreeMap<>();
    private final StringBuilder emitted = new StringBuilder();
    private int attempt;
    private int nextSeq;
    private boolean done;

    /**
     * Creates the assembler.
     *
     * @param requestId the request whose chunks it assembles
     * @param policy    decides restarts
     * @param listener  receives the stream
     */
    public ModelStreamAssembler(String requestId, StreamRestartPolicy policy, Listener listener) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Accepts one chunk, from a notification or a read-through.
     *
     * @param chunk the chunk
     */
    public synchronized void accept(ModelChunk chunk) {
        if (done || !requestId.equals(chunk.requestId()) || chunk.attempt() < attempt) {
            return;
        }
        if (chunk.attempt() > attempt) {
            if (attempt != 0 && nextSeq > 0) {
                StreamRestartPolicy.Action action = policy.decide(requestId, attempt, chunk.attempt(),
                        emitted.toString());
                if (action == StreamRestartPolicy.Action.FAIL) {
                    done = true;
                    listener.onError(new FleetStreamRestartedException(requestId, attempt, chunk.attempt(),
                            emitted.toString()));
                    return;
                }
                listener.onRestart(attempt, chunk.attempt());
            }
            attempt = chunk.attempt();
            nextSeq = 0;
            pending.clear();
            emitted.setLength(0);
        }
        if (chunk.seq() < nextSeq) {
            return;
        }
        pending.putIfAbsent(chunk.seq(), chunk);
        while (!done && pending.containsKey(nextSeq)) {
            ModelChunk next = pending.remove(nextSeq++);
            for (WireGeneration delta : next.deltas()) {
                if (delta.output() != null && delta.output().text() != null) {
                    emitted.append(delta.output().text());
                }
            }
            listener.onChunk(next);
            if (next.last()) {
                done = true;
                if (next.error() != null) {
                    listener.onError(new FleetModelException("model stream " + requestId + " failed: " + next.error()));
                } else {
                    listener.onComplete(next);
                }
            }
        }
    }

    /**
     * Fails the stream from outside, for a timeout; ignored once done.
     *
     * @param error the failure
     */
    public synchronized void fail(Throwable error) {
        if (!done) {
            done = true;
            listener.onError(error);
        }
    }

    /** Whether the stream has ended. */
    public synchronized boolean isDone() {
        return done;
    }

    /** The attempt being assembled; 0 before the first chunk. */
    public synchronized int attempt() {
        return attempt;
    }

    /** The text emitted in the current attempt. */
    public synchronized String emittedText() {
        return emitted.toString();
    }
}
