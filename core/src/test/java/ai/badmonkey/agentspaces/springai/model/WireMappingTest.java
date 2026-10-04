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

import ai.badmonkey.agentspaces.common.codec.CborCodec;
import ai.badmonkey.agentspaces.springai.model.wire.ModelRequest;
import ai.badmonkey.agentspaces.springai.model.wire.ModelResponse;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WireMappingTest {

    record In(String q) {
    }

    private final List<Message> conversation = List.of(
            new SystemMessage("be exact"),
            UserMessage.builder().text("look at this")
                    .media(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] {1, 2, 3}).name("p").build(),
                            Media.builder().mimeType(MimeTypeUtils.IMAGE_JPEG).data(URI.create("https://x.example/a.jpg")).build())
                    .build(),
            AssistantMessage.builder().content("calling").toolCalls(List.of(
                    new AssistantMessage.ToolCall("c1", "function", "lookup", "{\"q\":\"a\"}"))).build(),
            ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse("c1", "lookup", "{\"hit\":true}"))).build());

    @Test
    void everyMessageTypeRoundTrips() {
        List<Message> back = WireMapping.fromWire(WireMapping.toWire(conversation));
        assertThat(back).extracting(m -> m.getMessageType().getValue())
                .containsExactly("system", "user", "assistant", "tool");
        assertThat(back.get(0).getText()).isEqualTo("be exact");
        UserMessage user = (UserMessage) back.get(1);
        assertThat(user.getMedia()).hasSize(2);
        assertThat(user.getMedia().get(0).getDataAsByteArray()).containsExactly(1, 2, 3);
        assertThat(user.getMedia().get(0).getName()).isEqualTo("p");
        assertThat(user.getMedia().get(1).getData().toString()).isEqualTo("https://x.example/a.jpg");
        assertThat(((AssistantMessage) back.get(2)).getToolCalls()).singleElement()
                .satisfies(c -> assertThat(c.arguments()).isEqualTo("{\"q\":\"a\"}"));
        assertThat(((ToolResponseMessage) back.get(3)).getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).isEqualTo("{\"hit\":true}"));
    }

    @Test
    void optionsAndToolDefinitionsTravelAndServerToolsRefuseToRun() {
        ToolCallback tool = FunctionToolCallback.builder("lookup", (In in) -> "x")
                .description("looks things up").inputType(In.class).build();
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().model("m1").temperature(0.2)
                .maxTokens(99).stopSequences(List.of("END")).toolCallbacks(List.of(tool)).build();

        List<WireToolDefinition> tools = WireMapping.toolDefinitions(options);
        assertThat(tools).singleElement().satisfies(d -> {
            assertThat(d.name()).isEqualTo("lookup");
            assertThat(d.inputSchema()).contains("\"q\"");
        });
        ToolCallingChatOptions server = WireMapping.fromWire(WireMapping.toWire(options), "m1", tools);
        assertThat(server.getModel()).isEqualTo("m1");
        assertThat(server.getTemperature()).isEqualTo(0.2);
        assertThat(server.getMaxTokens()).isEqualTo(99);
        assertThat(server.getStopSequences()).containsExactly("END");
        assertThat(server.getToolCallbacks()).singleElement()
                .satisfies(c -> assertThat(c.getToolDefinition().description()).isEqualTo("looks things up"));
        assertThatThrownBy(() -> server.getToolCallbacks().get(0).call("{}"))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("runs on the caller");
    }

    @Test
    void aResponseRoundTripsWithUsageFinishReasonAndMetadata() {
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("answer").toolCalls(List.of(
                        new AssistantMessage.ToolCall("c9", "function", "t", "{}"))).build(),
                ChatGenerationMetadata.builder().finishReason("STOP").build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5, 15)).build());

        ChatResponse back = WireMapping.toResponse(WireMapping.generations(response), WireMapping.usage(response),
                "m1", "r1", Map.of("agentspaces.attempt", 2));
        assertThat(back.getResult().getOutput().getText()).isEqualTo("answer");
        assertThat(back.getResult().getOutput().getToolCalls()).hasSize(1);
        assertThat(back.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
        assertThat(back.getMetadata().getUsage().getTotalTokens()).isEqualTo(15);
        assertThat(back.getMetadata().getModel()).isEqualTo("m1");
        assertThat((Integer) back.getMetadata().get("agentspaces.attempt")).isEqualTo(2);
    }

    @Test
    void wireRecordsRoundTripThroughTheCanonicalCborCodec() {
        CborCodec codec = CborCodec.defaultCodec();
        ModelRequest request = new ModelRequest("r1", "m1", WireMapping.toWire(conversation),
                WireMapping.toWire(ToolCallingChatOptions.builder().model("m1").temperature(0.5).build()),
                List.of(new WireToolDefinition("t", "d", "{}")), true, "peer:z1");
        ModelRequest decoded = codec.fromBytes(codec.toBytes(request), ModelRequest.class);
        assertThat(decoded.messages()).hasSize(4);
        assertThat(decoded.messages().get(1).media().get(0).data()).containsExactly(1, 2, 3);
        assertThat(decoded.options().temperature()).isEqualTo(0.5);
        assertThat(decoded.stream()).isTrue();

        ModelResponse response = new ModelResponse("r1", "m1", List.of(), null, "boom", "peer:z2", 2);
        assertThat(codec.fromBytes(codec.toBytes(response), ModelResponse.class)).isEqualTo(response);
    }
}
