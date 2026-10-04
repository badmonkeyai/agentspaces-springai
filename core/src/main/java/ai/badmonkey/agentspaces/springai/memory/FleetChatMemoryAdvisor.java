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
package ai.badmonkey.agentspaces.springai.memory;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.Optional;

/**
 * Spring AI's {@link MessageChatMemoryAdvisor}, with the conversation ID filled
 * in. A call that names its conversation ({@code ChatMemory.CONVERSATION_ID})
 * keeps it; otherwise the {@link ConversationIdResolver} chooses one, and a call
 * the resolver cannot place passes through without memory instead of failing.
 * Worker code therefore never threads a conversation ID through its methods.
 */
public class FleetChatMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private final MessageChatMemoryAdvisor delegate;
    private final ConversationIdResolver resolver;

    /**
     * Creates the advisor.
     *
     * @param memory   the chat memory
     * @param resolver chooses the conversation when the call names none
     */
    public FleetChatMemoryAdvisor(ChatMemory memory, ConversationIdResolver resolver) {
        this.delegate = MessageChatMemoryAdvisor.builder(Objects.requireNonNull(memory, "memory")).build();
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public String getName() {
        return "agentspaces.chat-memory";
    }

    @Override
    public int getOrder() {
        return delegate.getOrder();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Optional<ChatClientRequest> placed = placed(request);
        return placed.isPresent() ? delegate.adviseCall(placed.get(), chain) : chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Optional<ChatClientRequest> placed = placed(request);
        return placed.isPresent() ? delegate.adviseStream(placed.get(), chain) : chain.nextStream(request);
    }

    private Optional<ChatClientRequest> placed(ChatClientRequest request) {
        if (request.context().get(ChatMemory.CONVERSATION_ID) != null) {
            return Optional.of(request);
        }
        return resolver.resolve().map(id -> request.mutate().context(ChatMemory.CONVERSATION_ID, id).build());
    }
}
