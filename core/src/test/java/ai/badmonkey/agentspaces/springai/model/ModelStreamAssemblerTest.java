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
import ai.badmonkey.agentspaces.springai.model.wire.WireMessage;
import ai.badmonkey.agentspaces.springai.model.wire.WireUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ModelStreamAssemblerTest {

    /** Records what the assembler emits. */
    static final class Recorder implements ModelStreamAssembler.Listener {
        final StringBuilder text = new StringBuilder();
        final List<String> events = new ArrayList<>();
        Throwable error;
        boolean complete;

        @Override
        public void onChunk(ModelChunk chunk) {
            chunk.deltas().forEach(d -> text.append(d.output().text()));
            events.add("chunk " + chunk.attempt() + "/" + chunk.seq());
        }

        @Override
        public void onRestart(int fromAttempt, int toAttempt) {
            text.append("|");
            events.add("restart " + fromAttempt + "->" + toAttempt);
        }

        @Override
        public void onComplete(ModelChunk last) {
            complete = true;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }
    }

    private static ModelChunk chunk(int attempt, int seq, boolean last, String... texts) {
        List<WireGeneration> deltas = new ArrayList<>();
        for (String text : texts) {
            deltas.add(new WireGeneration(new WireMessage("assistant", text, null, null, null, Map.of()), null));
        }
        return new ModelChunk("r1", attempt, seq, deltas, last, last ? new WireUsage(3, 4, 7) : null, null, "s1");
    }

    private static ModelStreamAssembler assembler(StreamRestartPolicy.Action action, Recorder recorder) {
        return new ModelStreamAssembler("r1", (id, from, to, partial) -> action, recorder);
    }

    @Test
    void shuffledAndDuplicatedChunksComeOutInOrderExactlyOnce() {
        List<ModelChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            chunks.add(chunk(1, i, i == 19, "t" + i + " "));
        }
        chunks.addAll(chunks.subList(3, 9));
        Collections.shuffle(chunks, new Random(7));
        Recorder recorder = new Recorder();
        ModelStreamAssembler assembler = assembler(StreamRestartPolicy.Action.FAIL, recorder);
        chunks.forEach(assembler::accept);

        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            expected.append("t").append(i).append(' ');
        }
        assertThat(recorder.text.toString()).isEqualTo(expected.toString());
        assertThat(recorder.complete).isTrue();
        assertThat(recorder.events).hasSize(20);
    }

    @Test
    void aGapWaitsForTheMissingChunk() {
        Recorder recorder = new Recorder();
        ModelStreamAssembler assembler = assembler(StreamRestartPolicy.Action.FAIL, recorder);
        assembler.accept(chunk(1, 0, false, "a"));
        assembler.accept(chunk(1, 2, true, "c"));
        assertThat(recorder.text.toString()).isEqualTo("a");
        assertThat(recorder.complete).isFalse();
        assembler.accept(chunk(1, 1, false, "b"));
        assertThat(recorder.text.toString()).isEqualTo("abc");
        assertThat(recorder.complete).isTrue();
    }

    @Test
    void aRestartBeforeAnythingWasEmittedIsSilent() {
        Recorder recorder = new Recorder();
        ModelStreamAssembler assembler = assembler(StreamRestartPolicy.Action.FAIL, recorder);
        assembler.accept(chunk(1, 1, false, "lost"));
        assembler.accept(chunk(2, 0, false, "fresh "));
        assembler.accept(chunk(2, 1, true, "start"));
        assertThat(recorder.error).isNull();
        assertThat(recorder.text.toString()).isEqualTo("fresh start");
        assertThat(recorder.events).noneMatch(e -> e.startsWith("restart"));
    }

    @Test
    void underFailARestartEndsTheStreamWithThePartialText() {
        Recorder recorder = new Recorder();
        ModelStreamAssembler assembler = assembler(StreamRestartPolicy.Action.FAIL, recorder);
        assembler.accept(chunk(1, 0, false, "half an "));
        assembler.accept(chunk(2, 0, false, "other"));
        assertThat(recorder.error).isInstanceOf(FleetStreamRestartedException.class);
        FleetStreamRestartedException restarted = (FleetStreamRestartedException) recorder.error;
        assertThat(restarted.partialText()).isEqualTo("half an ");
        assertThat(restarted.fromAttempt()).isEqualTo(1);
        assertThat(restarted.toAttempt()).isEqualTo(2);
        assembler.accept(chunk(2, 1, true, "ignored"));
        assertThat(recorder.complete).isFalse();
    }

    @Test
    void underRestartTheNewAttemptFollowsAMarkerAndLateOldChunksAreIgnored() {
        Recorder recorder = new Recorder();
        ModelStreamAssembler assembler = assembler(StreamRestartPolicy.Action.RESTART, recorder);
        assembler.accept(chunk(1, 0, false, "old "));
        assembler.accept(chunk(2, 0, false, "new "));
        assembler.accept(chunk(1, 1, false, "zombie "));
        assembler.accept(chunk(2, 1, true, "answer"));
        assertThat(recorder.text.toString()).isEqualTo("old |new answer");
        assertThat(recorder.events).contains("restart 1->2");
        assertThat(recorder.complete).isTrue();
        assertThat(assembler.emittedText()).isEqualTo("new answer");
    }

    @Test
    void aFailedLastChunkIsAnErrorAndAnExternalFailureEndsTheStreamOnce() {
        Recorder failed = new Recorder();
        ModelStreamAssembler assembler = assembler(StreamRestartPolicy.Action.FAIL, failed);
        assembler.accept(new ModelChunk("r1", 1, 0, List.of(), true, null, "rate limited", "s1"));
        assertThat(failed.error).isInstanceOf(FleetModelException.class).hasMessageContaining("rate limited");

        Recorder timedOut = new Recorder();
        ModelStreamAssembler slow = assembler(StreamRestartPolicy.Action.FAIL, timedOut);
        slow.fail(new FleetModelException("timeout"));
        slow.accept(chunk(1, 0, true, "late"));
        assertThat(timedOut.error).hasMessage("timeout");
        assertThat(timedOut.text.toString()).isEmpty();
    }
}
