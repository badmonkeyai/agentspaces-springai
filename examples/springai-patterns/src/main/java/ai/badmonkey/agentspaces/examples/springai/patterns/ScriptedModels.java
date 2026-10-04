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
package ai.badmonkey.agentspaces.examples.springai.patterns;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.function.Function;

/**
 * Scripted stand-ins for real models, so the patterns run offline and their
 * tests are deterministic. Each pattern takes a {@code ChatClient.Builder}, so
 * a real Spring AI provider drops in with no other change.
 */
public final class ScriptedModels {

    private ScriptedModels() {
    }

    /**
     * A ChatClient builder over a model that answers the prompt's text with a function.
     *
     * @param answer maps the full prompt text to the answer
     * @return the builder
     */
    public static ChatClient.Builder answering(Function<String, String> answer) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(prompt.getContents())))));
            }
        };
        return ChatClient.builder(model);
    }
}
