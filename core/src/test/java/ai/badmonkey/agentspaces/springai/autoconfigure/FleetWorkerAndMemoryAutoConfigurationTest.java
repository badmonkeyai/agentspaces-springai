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

import ai.badmonkey.agentspaces.agent.TakeContext;
import ai.badmonkey.agentspaces.springai.memory.ConversationIdResolver;
import ai.badmonkey.agentspaces.springai.memory.FleetChatMemoryAdvisor;
import ai.badmonkey.agentspaces.springai.memory.SpaceChatMemoryRepository;
import ai.badmonkey.agentspaces.springai.support.FakeTake;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.springai.worker.TakeContextAccessor;
import ai.badmonkey.agentspaces.springai.worker.TakeLeaseAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FleetWorkerAndMemoryAutoConfigurationTest {

    private static ApplicationContextRunner runner(String... spaces) {
        return FleetApps.runner(spaces)
                .withConfiguration(AutoConfigurations.of(ChatClientAutoConfiguration.class,
                        ChatMemoryAutoConfiguration.class))
                .withUserConfiguration(ModelConfig.class);
    }

    @Test
    void everyAutoConfiguredChatClientRenewsTheWorkersTake() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TakeLeaseAdvisor.class);
            assertThat(context).hasSingleBean(TakeContextAccessor.class);
            ChatClient chat = context.getBean(ChatClient.Builder.class).build();
            FakeTake take = new FakeTake();
            TakeContext.bind(take.context("worker"));
            try {
                chat.prompt().user("hello").call().content();
            } finally {
                TakeContext.unbind();
            }
            assertThat(take.renewals).as("the customizer added the advisor").hasValue(1);
        });
    }

    @Test
    void theTakeLeaseAdvisorCanBeSwitchedOff() {
        runner().withPropertyValues("agentspaces.springai.take-lease.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TakeLeaseAdvisor.class);
        });
    }

    @Test
    void memoryIsOffByDefaultAndSpringAisInMemoryDefaultApplies() {
        runner().run(context -> {
            assertThat(context).doesNotHaveBean(FleetChatMemoryAdvisor.class);
            assertThat(context.getBean(ChatMemoryRepository.class)).isInstanceOf(InMemoryChatMemoryRepository.class);
        });
    }

    @Test
    void enabledMemoryLivesInTheSpaceAndSpringAisDefaultBacksOff() {
        runner("work", "conversations").withPropertyValues("agentspaces.springai.memory.enabled=true",
                        "agentspaces.springai.memory.conversation-id=agent")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatMemoryRepository.class)).isInstanceOf(SpaceChatMemoryRepository.class);
                    assertThat(context).hasSingleBean(FleetChatMemoryAdvisor.class);
                    FakeTake take = new FakeTake();
                    TakeContext worker = take.context("researcher");
                    TakeContext.bind(worker);
                    try {
                        assertThat(context.getBean(ConversationIdResolver.class).resolve())
                                .contains(worker.agent().encoded());
                        context.getBean(ChatClient.Builder.class).build().prompt().user("remember").call().content();
                    } finally {
                        TakeContext.unbind();
                    }
                    assertThat(context.getBean(SpaceChatMemoryRepository.class)
                            .findByConversationId(worker.agent().encoded())).hasSize(2);
                });
    }

    /**
     * A conversation larger than the 64 KiB inline limit: the space refuses such
     * an entry unless it travels content-addressed over the group's block
     * exchange, so saving and reading it back proves the CID path.
     */
    @Test
    void aConversationLargerThanTheInlineLimitTravelsContentAddressed() {
        runner("work", "conversations").withPropertyValues("agentspaces.springai.memory.enabled=true")
                .run(context -> {
                    SpaceChatMemoryRepository repository = context.getBean(SpaceChatMemoryRepository.class);
                    String long_ = "x".repeat(100 * 1024);
                    repository.saveAll("big", java.util.List.of(
                            new org.springframework.ai.chat.messages.UserMessage(long_)));
                    assertThat(repository.findByConversationId("big")).singleElement()
                            .satisfies(m -> assertThat(m.getText()).hasSize(100 * 1024));
                });
    }

    @Test
    void enabledMemoryWithoutItsSpaceFailsWithTheYamlThatAddsIt() {
        runner().withPropertyValues("agentspaces.springai.memory.enabled=true").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("agentspaces.springai.memory.space")
                        .hasMessageContaining("- name: conversations"));
    }

    @Test
    void anApplicationRepositoryOrResolverReplacesTheDefault() {
        runner("work", "conversations").withPropertyValues("agentspaces.springai.memory.enabled=true")
                .withUserConfiguration(Overrides.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatMemoryRepository.class)).isSameAs(Overrides.REPOSITORY);
                    assertThat(context).doesNotHaveBean(SpaceChatMemoryRepository.class);
                    assertThat(context.getBean(ConversationIdResolver.class)).isSameAs(Overrides.RESOLVER);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ModelConfig {
        @Bean
        ChatModel chatModel() {
            return ScriptedChatModel.answering("ok");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Overrides {
        static final ChatMemoryRepository REPOSITORY = new InMemoryChatMemoryRepository();
        static final ConversationIdResolver RESOLVER = () -> Optional.of("tenant-1");

        @Bean
        ChatMemoryRepository repository() {
            return REPOSITORY;
        }

        @Bean
        ConversationIdResolver resolver() {
            return RESOLVER;
        }
    }
}
