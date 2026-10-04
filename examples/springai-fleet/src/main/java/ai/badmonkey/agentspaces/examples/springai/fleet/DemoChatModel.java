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
package ai.badmonkey.agentspaces.examples.springai.fleet;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;

/**
 * A scripted stand-in for a real model, so the example runs offline and its
 * test is deterministic. As an orchestrator it calls the two fleet tools, then
 * writes the briefing from their results; as a worker it summarizes or
 * translates. A real Spring AI provider replaces it with no code change.
 */
final class DemoChatModel implements ChatModel {

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        Message last = messages.get(messages.size() - 1);
        return new ChatResponse(List.of(new Generation(answer(prompt, last))));
    }

    private static AssistantMessage answer(Prompt prompt, Message last) {
        if (last instanceof ToolResponseMessage tools) {
            StringBuilder briefing = new StringBuilder("Here is your briefing.");
            tools.getResponses().forEach(r -> briefing.append(' ').append(r.responseData()));
            return new AssistantMessage(briefing.toString());
        }
        String text = last instanceof UserMessage user ? user.getText() : "";
        if (text.startsWith("Summarize: ")) {
            String topic = text.substring("Summarize: ".length());
            return new AssistantMessage(topic + " coordinate agents through a shared, leased space.");
        }
        if (text.startsWith("Translate to ")) {
            String rest = text.substring("Translate to ".length());
            String language = rest.substring(0, rest.indexOf(':'));
            return new AssistantMessage("[" + language + "] " + rest.substring(rest.indexOf(':') + 2));
        }
        if (offersFleetTools(prompt)) {
            return AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("c1", "function", "fleet_summarizer_Brief",
                            "{\"topic\":\"tuple spaces\"}"),
                    new AssistantMessage.ToolCall("c2", "function", "fleet_translator_TranslateTask",
                            "{\"text\":\"tuple spaces\",\"language\":\"fr\"}"))).build();
        }
        return new AssistantMessage("I have no fleet tools to call.");
    }

    private static boolean offersFleetTools(Prompt prompt) {
        return prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks().stream()
                        .anyMatch(t -> t.getToolDefinition().name().startsWith("fleet_"));
    }
}
