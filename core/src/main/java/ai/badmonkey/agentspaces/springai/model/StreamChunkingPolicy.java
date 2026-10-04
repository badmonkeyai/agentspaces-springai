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

import java.time.Duration;

/**
 * How a model server batches streamed deltas into chunk entries: a long answer
 * then writes tens of entries rather than thousands. Declare a bean of this type
 * to trade latency against entry count.
 */
public interface StreamChunkingPolicy {

    /**
     * Whether the buffered deltas should be written now.
     *
     * @param buffered       deltas waiting
     * @param sinceLastFlush time since the last chunk was written
     * @return whether to write a chunk
     */
    boolean flush(int buffered, Duration sinceLastFlush);

    /** How often the server checks a slow stream for deltas to flush. */
    Duration interval();

    /**
     * The default: flush when {@code maxDeltas} are waiting or {@code interval}
     * has passed.
     *
     * @param interval  the longest a delta waits
     * @param maxDeltas the most deltas one chunk carries
     * @return the policy
     */
    static StreamChunkingPolicy of(Duration interval, int maxDeltas) {
        return new StreamChunkingPolicy() {
            @Override
            public boolean flush(int buffered, Duration sinceLastFlush) {
                return buffered >= maxDeltas || (buffered > 0 && sinceLastFlush.compareTo(interval) >= 0);
            }

            @Override
            public Duration interval() {
                return interval;
            }
        };
    }
}
