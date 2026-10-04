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

import ai.badmonkey.agentspaces.agent.TakeContext;
import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.ConversationIdMode;
import ai.badmonkey.agentspaces.springai.support.FakeTake;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FleetChatMemoryAdvisorTest {

    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).build();

    @Test
    void theResolverKeysByTakeByAgentOrNotAtAll() {
        FakeTake take = new FakeTake();
        TakeContext context = take.context("researcher");
        TakeContext.bind(context);
        try {
            assertThat(ConversationIdResolver.of(ConversationIdMode.TAKE).resolve())
                    .contains(take.entryId().value());
            assertThat(ConversationIdResolver.of(ConversationIdMode.AGENT).resolve())
                    .contains(context.agent().encoded());
            assertThat(ConversationIdResolver.of(ConversationIdMode.EXPLICIT).resolve()).isEmpty();
        } finally {
            TakeContext.unbind();
        }
        assertThat(ConversationIdResolver.of(ConversationIdMode.TAKE).resolve()).isEmpty();
    }

    @Test
    void aResolvedConversationIsRememberedAcrossCalls() {
        ScriptedChatModel model = ScriptedChatModel.answering("noted");
        ChatClient chat = ChatClient.builder(model)
                .defaultAdvisors(new FleetChatMemoryAdvisor(memory, () -> Optional.of("task-7"))).build();

        chat.prompt().user("first").call().content();
        chat.prompt().user("second").call().content();

        assertThat(model.prompts.get(1).getInstructions()).extracting(Message::getText)
                .containsExactly("first", "noted", "second");
        assertThat(memory.get("task-7")).hasSize(4);
    }

    @Test
    void anExplicitConversationWinsAndAnUnplacedCallPassesThrough() {
        ScriptedChatModel model = ScriptedChatModel.answering("ok");
        ChatClient chat = ChatClient.builder(model)
                .defaultAdvisors(new FleetChatMemoryAdvisor(memory, Optional::empty)).build();

        chat.prompt().user("no memory").call().content();
        assertThat(memory.get("anything")).isEmpty();

        chat.prompt().user("mine").advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "user-9")).call().content();
        assertThat(memory.get("user-9")).extracting(Message::getText).containsExactly("mine", "ok");
    }
}
