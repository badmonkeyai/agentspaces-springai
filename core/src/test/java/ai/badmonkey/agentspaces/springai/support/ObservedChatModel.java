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
package ai.badmonkey.agentspaces.springai.support;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * A provider stand-in that instruments itself the way Spring AI's providers do:
 * each call is a {@code gen_ai.client.operation} observation carrying the
 * response and its token usage.
 */
public class ObservedChatModel implements ChatModel {

    private final ObservationRegistry registry;
    private final String model;
    private final int promptTokens;
    private final int completionTokens;

    public ObservedChatModel(ObservationRegistry registry, String model, int promptTokens, int completionTokens) {
        this.registry = registry;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatModelObservationContext context = ChatModelObservationContext.builder()
                .prompt(prompt).provider("scripted").build();
        return Observation.createNotStarted("gen_ai.client.operation", () -> context, registry).observe(() -> {
            ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))),
                    ChatResponseMetadata.builder().model(model)
                            .usage(new DefaultUsage(promptTokens, completionTokens,
                                    promptTokens + completionTokens)).build());
            context.setResponse(response);
            return response;
        });
    }
}
