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
import ai.badmonkey.agentspaces.springai.model.wire.WireOptions;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolCall;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolDefinition;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolResponse;
import ai.badmonkey.agentspaces.springai.model.wire.WireUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.MimeType;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between Spring AI's model types and the wire records. The caller
 * side maps a {@code Prompt} out and a {@code ChatResponse} back in; the server
 * side does the reverse. Tool definitions travel so the server's model can ask
 * for tool calls, but a server rebuilds them as definition-only callbacks that
 * refuse to run: in Spring AI 2.0 a {@code ChatModel} call never executes tools,
 * and the caller's own tool loop runs them.
 */
public final class WireMapping {

    private WireMapping() {
    }

    // ---------------------------------------------------------------- messages

    /** Maps a message to the wire. */
    public static WireMessage toWire(Message message) {
        Map<String, String> metadata = stringMetadata(message.getMetadata());
        return switch (message) {
            case UserMessage user -> new WireMessage("user", user.getText(), List.of(), List.of(),
                    media(user.getMedia()), metadata);
            case SystemMessage system -> new WireMessage("system", system.getText(), List.of(),
                    List.of(), List.of(), metadata);
            case AssistantMessage assistant -> new WireMessage("assistant", assistant.getText(),
                    assistant.getToolCalls().stream()
                            .map(c -> new WireToolCall(c.id(), c.type(), c.name(), c.arguments()))
                            .toList(),
                    List.of(), media(assistant.getMedia()), metadata);
            case ToolResponseMessage tool -> new WireMessage("tool", null, List.of(),
                    tool.getResponses().stream()
                            .map(r -> new WireToolResponse(r.id(), r.name(), r.responseData()))
                            .toList(),
                    List.of(), metadata);
            default -> new WireMessage(message.getMessageType().getValue(), message.getText(),
                    List.of(), List.of(), List.of(), metadata);
        };
    }

    /** Maps a wire message back. */
    public static Message fromWire(WireMessage message) {
        Map<String, Object> metadata = new LinkedHashMap<>(message.metadata());
        return switch (message.role()) {
            case "system" -> SystemMessage.builder().text(text(message)).metadata(metadata).build();
            case "assistant" -> AssistantMessage.builder()
                    .content(message.text())
                    .properties(metadata)
                    .toolCalls(message.toolCalls().stream()
                            .map(c -> new AssistantMessage.ToolCall(c.id(), c.type(), c.name(), c.arguments()))
                            .toList())
                    .media(fromWireMedia(message.media()))
                    .build();
            case "tool" -> ToolResponseMessage.builder()
                    .responses(message.toolResponses().stream()
                            .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.responseData()))
                            .toList())
                    .metadata(metadata)
                    .build();
            default -> UserMessage.builder().text(text(message)).media(fromWireMedia(message.media()))
                    .metadata(metadata).build();
        };
    }

    /** Maps a list of messages to the wire. */
    public static List<WireMessage> toWire(List<Message> messages) {
        return messages.stream().map(WireMapping::toWire).toList();
    }

    /** Maps a list of wire messages back. */
    public static List<Message> fromWire(List<WireMessage> messages) {
        return messages.stream().map(WireMapping::fromWire).toList();
    }

    // ---------------------------------------------------------------- options and tools

    /** Maps the portable part of chat options to the wire; null options map to null. */
    public static WireOptions toWire(ChatOptions options) {
        if (options == null) {
            return null;
        }
        return new WireOptions(options.getModel(), options.getTemperature(), options.getMaxTokens(),
                options.getTopP(), options.getTopK(), options.getFrequencyPenalty(),
                options.getPresencePenalty(), options.getStopSequences());
    }

    /** The tool definitions a prompt's options carry. */
    public static List<WireToolDefinition> toolDefinitions(ChatOptions options) {
        if (options instanceof ToolCallingChatOptions tools && tools.getToolCallbacks() != null) {
            return tools.getToolCallbacks().stream()
                    .map(ToolCallback::getToolDefinition)
                    .map(d -> new WireToolDefinition(d.name(), d.description(), d.inputSchema()))
                    .toList();
        }
        return List.of();
    }

    /**
     * Rebuilds chat options on the server: the portable values, the model to
     * call, and definition-only tool callbacks.
     *
     * @param options the wire options, or null
     * @param model   the model to call
     * @param tools   the tool definitions
     * @return the options
     */
    public static ToolCallingChatOptions fromWire(WireOptions options, String model,
                                                  List<WireToolDefinition> tools) {
        ToolCallingChatOptions.Builder<?> builder = ToolCallingChatOptions.builder().model(model);
        if (options != null) {
            builder.temperature(options.temperature()).maxTokens(options.maxTokens())
                    .topP(options.topP()).topK(options.topK())
                    .frequencyPenalty(options.frequencyPenalty())
                    .presencePenalty(options.presencePenalty());
            if (options.stopSequences() != null) {
                builder.stopSequences(options.stopSequences());
            }
        }
        if (!tools.isEmpty()) {
            builder.toolCallbacks(tools.stream().<ToolCallback>map(DefinitionOnlyToolCallback::new).toList());
        }
        return builder.build();
    }

    /** A tool known by its definition only: the caller runs it, never the server. */
    public record DefinitionOnlyToolCallback(WireToolDefinition wire) implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(wire.name()).description(wire.description())
                    .inputSchema(wire.inputSchema()).build();
        }

        @Override
        public String call(String toolInput) {
            throw new UnsupportedOperationException("tool '" + wire.name()
                    + "' runs on the caller; a model server never executes tools");
        }
    }

    // ---------------------------------------------------------------- responses

    /** Maps a response's generations to the wire. */
    public static List<WireGeneration> generations(ChatResponse response) {
        List<WireGeneration> generations = new ArrayList<>();
        for (Generation generation : response.getResults()) {
            String finish = generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
            generations.add(new WireGeneration(toWire(generation.getOutput()), finish));
        }
        return generations;
    }

    /** Maps a response's usage to the wire, or null when it reports none. */
    public static WireUsage usage(ChatResponse response) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        return new WireUsage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    /**
     * Rebuilds a response on the caller.
     *
     * @param generations the generations
     * @param usage       the usage, or null
     * @param model       the model that answered
     * @param id          the response id
     * @return the response
     */
    public static ChatResponse toResponse(List<WireGeneration> generations, WireUsage usage,
                                          String model, String id) {
        return toResponse(generations, usage, model, id, Map.of());
    }

    /**
     * Rebuilds a response on the caller, with extra metadata.
     *
     * @param generations the generations
     * @param usage       the usage, or null
     * @param model       the model that answered
     * @param id          the response id
     * @param extra       extra metadata, for example the serving peer and attempt
     * @return the response
     */
    public static ChatResponse toResponse(List<WireGeneration> generations, WireUsage usage,
                                          String model, String id, Map<String, Object> extra) {
        List<Generation> results = new ArrayList<>();
        for (WireGeneration generation : generations) {
            AssistantMessage output = generation.output() == null ? new AssistantMessage("")
                    : (AssistantMessage) fromWire(assistant(generation.output()));
            results.add(generation.finishReason() == null ? new Generation(output)
                    : new Generation(output, ChatGenerationMetadata.builder()
                            .finishReason(generation.finishReason()).build()));
        }
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().id(id);
        extra.forEach(metadata::keyValue);
        if (model != null) {
            metadata.model(model);
        }
        if (usage != null) {
            metadata.usage(new DefaultUsage(usage.promptTokens(), usage.completionTokens(),
                    usage.totalTokens()));
        }
        return new ChatResponse(results, metadata.build());
    }

    // ---------------------------------------------------------------- internals

    private static WireMessage assistant(WireMessage message) {
        return "assistant".equals(message.role()) ? message
                : new WireMessage("assistant", message.text(), message.toolCalls(), List.of(),
                        message.media(), message.metadata());
    }

    private static String text(WireMessage message) {
        return message.text() == null ? "" : message.text();
    }

    private static Map<String, String> stringMetadata(Map<String, Object> metadata) {
        Map<String, String> out = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    out.put(key, String.valueOf(value));
                }
            });
        }
        return out;
    }

    private static List<WireMedia> media(List<Media> media) {
        if (media == null || media.isEmpty()) {
            return List.of();
        }
        List<WireMedia> out = new ArrayList<>();
        for (Media item : media) {
            Object data = item.getData();
            String mime = item.getMimeType() == null ? null : item.getMimeType().toString();
            if (data instanceof byte[] bytes) {
                out.add(new WireMedia(mime, item.getName(), bytes, null));
            } else if (data instanceof URI || data instanceof URL) {
                out.add(new WireMedia(mime, item.getName(), null, data.toString()));
            } else if (data instanceof String text && (text.startsWith("http:") || text.startsWith("https:"))) {
                out.add(new WireMedia(mime, item.getName(), null, text));
            } else {
                out.add(new WireMedia(mime, item.getName(), item.getDataAsByteArray(), null));
            }
        }
        return out;
    }

    private static List<Media> fromWireMedia(List<WireMedia> media) {
        List<Media> out = new ArrayList<>();
        for (WireMedia item : media) {
            Media.Builder builder = Media.builder().mimeType(MimeType.valueOf(item.mimeType()));
            if (item.name() != null) {
                builder.name(item.name());
            }
            if (item.data() != null) {
                builder.data(item.data());
            } else {
                builder.data(URI.create(item.url()));
            }
            out.add(builder.build());
        }
        return out;
    }
}
