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

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A {@link ChatModel} that answers from a script and records every prompt, so
 * tests assert on exactly what the model saw (messages and tool definitions)
 * without a live provider.
 */
public class ScriptedChatModel implements ChatModel {

    private final Function<Prompt, AssistantMessage> script;
    private volatile Function<Prompt, reactor.core.publisher.Flux<ChatResponse>> streamer;
    public final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    public ScriptedChatModel(Function<Prompt, AssistantMessage> script) {
        this.script = script;
    }

    /** Streams with the given function; without one, a stream is the call's single response. */
    public ScriptedChatModel streaming(Function<Prompt, reactor.core.publisher.Flux<ChatResponse>> streamer) {
        this.streamer = streamer;
        return this;
    }

    /** A streamer that emits each text as one chunk, {@code every} apart. */
    public static Function<Prompt, reactor.core.publisher.Flux<ChatResponse>> chunks(
            java.time.Duration every, String... texts) {
        return prompt -> reactor.core.publisher.Flux.fromArray(texts)
                .delayElements(every)
                .map(text -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    /** Always answers with the same text. */
    public static ScriptedChatModel answering(String text) {
        return new ScriptedChatModel(prompt -> new AssistantMessage(text));
    }

    /**
     * Calls the named tools (with the given JSON arguments) on the first round,
     * then answers with every tool response it received.
     */
    public static ScriptedChatModel callingTools(List<AssistantMessage.ToolCall> calls) {
        return new ScriptedChatModel(prompt -> {
            Message last = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
            if (last instanceof ToolResponseMessage responses) {
                StringBuilder text = new StringBuilder("results:");
                responses.getResponses().forEach(r -> text.append(' ').append(r.name())
                        .append('=').append(r.responseData()));
                return new AssistantMessage(text.toString());
            }
            return AssistantMessage.builder().content("").toolCalls(calls).build();
        });
    }

    /**
     * Like every tool-capable provider, the options are tool-calling options:
     * ChatClient (2.0.1) mutates {@code getOptions()} and attaches tools only
     * to a {@code ToolCallingChatOptions.Builder}.
     */
    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        return new ChatResponse(List.of(new Generation(script.apply(prompt))));
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
        Function<Prompt, reactor.core.publisher.Flux<ChatResponse>> current = streamer;
        if (current == null) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
        prompts.add(prompt);
        return current.apply(prompt);
    }

    /** The tool names the given prompt offered the model. */
    public static List<String> toolNames(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            return options.getToolCallbacks().stream()
                    .map(ToolCallback::getToolDefinition).map(d -> d.name()).toList();
        }
        return List.of();
    }
}
