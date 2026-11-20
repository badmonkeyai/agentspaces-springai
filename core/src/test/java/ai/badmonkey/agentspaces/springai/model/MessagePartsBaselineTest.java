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

import ai.badmonkey.agentspaces.springai.model.wire.WireGeneration;
import ai.badmonkey.agentspaces.springai.model.wire.WireMedia;
import ai.badmonkey.agentspaces.springai.model.wire.WireMessage;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how the Spring AI 2.1 message parts meet today's wire records (review
 * {@code reviews/spring-ai-2.md} §9.2): the parts an assistant message is rebuilt
 * with, and the stream deltas the model server keeps off the wire until the
 * records carry parts.
 */
class MessagePartsBaselineTest {

    private static final AssistantMessage.ToolCall CALL =
            new AssistantMessage.ToolCall("c1", "function", "lookup", "{\"q\":\"a\"}");

    @Test
    void anAssistantMessageIsRebuiltAsTextThenToolCallsThenMedia() {
        WireMessage wire = new WireMessage("assistant", "calling",
                List.of(new WireToolCall("c1", "function", "lookup", "{\"q\":\"a\"}"),
                        new WireToolCall("c2", "function", "fetch", "{}")),
                List.of(), List.of(new WireMedia("image/png", "p", new byte[] {1, 2, 3}, null)), Map.of());

        AssistantMessage back = (AssistantMessage) WireMapping.fromWire(wire);

        assertThat(kinds(back)).containsExactly(TextPart.class, ToolCallPart.class, ToolCallPart.class, MediaPart.class);
        assertThat(back.getText()).isEqualTo("calling");
        assertThat(back.getToolCalls()).extracting(AssistantMessage.ToolCall::id).containsExactly("c1", "c2");
        assertThat(back.getMedia()).hasSize(1);
    }

    @Test
    void reasoningAndPartialToolCallChunksAreEmptyDeltas() {
        AssistantMessage reasoning = AssistantMessage.builder().reasoning(ReasoningPart.of("thinking")).build();
        AssistantMessage partialCall = AssistantMessage.builder()
                .part(StreamingParts.partial(ToolCallPart.of(CALL), 0)).build();

        assertThat(ModelServer.empty(only(reasoning))).isTrue();
        assertThat(ModelServer.empty(only(partialCall))).isTrue();
        assertThat(WireMapping.toWire(partialCall).toolCalls()).isEmpty();
    }

    @Test
    void textCompleteToolCallsAndFinishReasonsAreKept() {
        AssistantMessage text = AssistantMessage.builder().content("hi").build();
        AssistantMessage completeCall = AssistantMessage.builder().part(ToolCallPart.of(CALL)).build();
        Generation finished = new Generation(AssistantMessage.builder().build(),
                ChatGenerationMetadata.builder().finishReason("STOP").build());

        assertThat(ModelServer.empty(only(text))).isFalse();
        assertThat(ModelServer.empty(only(completeCall))).isFalse();
        assertThat(ModelServer.empty(WireMapping.generations(new ChatResponse(List.of(finished))).get(0))).isFalse();
    }

    private static WireGeneration only(AssistantMessage message) {
        return WireMapping.generations(new ChatResponse(List.of(new Generation(message)))).get(0);
    }

    private static List<Class<? extends MessagePart>> kinds(AssistantMessage message) {
        return message.getParts().stream().<Class<? extends MessagePart>>map(MessagePart::getClass).toList();
    }
}
