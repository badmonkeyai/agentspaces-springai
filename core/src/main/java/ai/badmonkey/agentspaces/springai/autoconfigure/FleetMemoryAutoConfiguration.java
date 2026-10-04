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
package ai.badmonkey.agentspaces.springai.autoconfigure;

import ai.badmonkey.agentspaces.springai.FleetContext;
import ai.badmonkey.agentspaces.springai.memory.ConversationIdResolver;
import ai.badmonkey.agentspaces.springai.memory.FleetChatMemoryAdvisor;
import ai.badmonkey.agentspaces.springai.memory.SpaceChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * F3, chat memory in a replicated space: a {@link SpaceChatMemoryRepository}
 * over the configured space, the {@code ChatMemory} window over it, the
 * {@link ConversationIdResolver}, and a {@link FleetChatMemoryAdvisor} added to
 * every auto-configured {@code ChatClient.Builder}. An application's own
 * {@code ChatMemoryRepository} or {@code ChatMemory} bean replaces the one here.
 * Ordered before Spring AI's chat-memory auto-configuration, so its in-memory
 * default backs off.
 */
@AutoConfiguration(after = AgentSpacesSpringAiAutoConfiguration.class,
        beforeName = "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration")
@ConditionalOnClass(ChatClient.class)
@ConditionalOnBean(FleetContext.class)
@ConditionalOnProperty(prefix = "agentspaces.springai.memory", name = "enabled", havingValue = "true")
public class FleetMemoryAutoConfiguration {

    /**
     * Chat memory in the configured space.
     *
     * @param fleet      the fleet context
     * @param properties the properties
     * @return the repository
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemoryRepository.class)
    public SpaceChatMemoryRepository spaceChatMemoryRepository(FleetContext fleet,
                                                               AgentSpacesSpringAiProperties properties) {
        AgentSpacesSpringAiProperties.Memory memory = properties.memory();
        return new SpaceChatMemoryRepository(
                fleet.requiredSpace(memory.space(), "agentspaces.springai.memory.space"), memory.ttl());
    }

    /**
     * The message window over the repository.
     *
     * @param repository the repository
     * @param properties the properties
     * @return the chat memory
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatMemory chatMemory(ChatMemoryRepository repository, AgentSpacesSpringAiProperties properties) {
        return MessageWindowChatMemory.builder().chatMemoryRepository(repository)
                .maxMessages(properties.memory().maxMessages()).build();
    }

    /**
     * The default conversation key, from {@code memory.conversation-id}.
     *
     * @param properties the properties
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean
    public ConversationIdResolver conversationIdResolver(AgentSpacesSpringAiProperties properties) {
        return ConversationIdResolver.of(properties.memory().conversationId());
    }

    /**
     * The memory advisor.
     *
     * @param memory   the chat memory
     * @param resolver the conversation resolver
     * @return the advisor
     */
    @Bean
    @ConditionalOnMissingBean
    public FleetChatMemoryAdvisor fleetChatMemoryAdvisor(ChatMemory memory, ConversationIdResolver resolver) {
        return new FleetChatMemoryAdvisor(memory, resolver);
    }

    /**
     * Adds the memory advisor to every auto-configured ChatClient.
     *
     * @param advisor the advisor
     * @return the customizer
     */
    @Bean
    public ChatClientBuilderCustomizer fleetChatMemoryChatClientCustomizer(FleetChatMemoryAdvisor advisor) {
        return builder -> builder.defaultAdvisors(advisor);
    }
}
