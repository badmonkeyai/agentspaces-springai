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

import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Model access between two real Spring Boot applications: a caller that has its
 * own provider {@code ChatModel} and injects {@code fleetChatModel} by name into
 * a second {@code ChatClient}, and a server that serves its scripted provider to
 * the fleet. The caller is the {@code @SpringBootTest} context; the server is a
 * second Boot application started before it. Beside a provider, the provider
 * stays the {@code ChatModel} by type, and the fleet model is reached by name.
 */
@SpringBootTest(classes = FleetModelSpringBootTest.CallerApp.class)
class FleetModelSpringBootTest {

    private static final String FOUNDING = "springai-model-boot-v1";

    static int serverPort;
    static ConfigurableApplicationContext server;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ServerApp {
        @Bean
        ScriptedChatModel servedModel() {
            return ScriptedChatModel.answering("answer from the fleet")
                    .streaming(ScriptedChatModel.chunks(Duration.ofMillis(20), "streamed ", "from ", "the fleet"));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CallerApp {
        @Bean
        ScriptedChatModel localModel() {
            return ScriptedChatModel.answering("answer from the local provider");
        }

        @Bean
        ChatClient fleetChat(@Qualifier("fleetChatModel") ChatModel fleet) {
            return ChatClient.create(fleet);
        }
    }

    @BeforeAll
    static void startServer() {
        String[] node = FleetApps.nodeProperties(FOUNDING, 0, "model-requests");
        serverPort = Integer.parseInt(java.util.Arrays.stream(node)
                .filter(p -> p.startsWith("agentspaces.bind=")).findFirst().orElseThrow()
                .substring("agentspaces.bind=127.0.0.1:".length()));
        server = new SpringApplicationBuilder(ServerApp.class)
                .properties(node)
                .properties(Map.of("agentspaces.springai.model-server.enabled", "true",
                        "agentspaces.springai.model-server.models", "test-model",
                        "agentspaces.springai.model-server.chunk-interval", "20ms"))
                .run();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @DynamicPropertySource
    static void callerProperties(DynamicPropertyRegistry registry) {
        for (String property : FleetApps.nodeProperties(FOUNDING, serverPort, "model-requests")) {
            int split = property.indexOf('=');
            registry.add(property.substring(0, split), () -> property.substring(split + 1));
        }
        registry.add("agentspaces.springai.model-client.enabled", () -> "true");
        registry.add("agentspaces.springai.model-client.timeout", () -> "30s");
    }

    @Autowired
    ChatModel byType;

    @Autowired
    ChatClient.Builder autoConfiguredBuilder;

    @Autowired
    @Qualifier("fleetChat")
    ChatClient fleetChat;

    @Autowired
    @Qualifier("localModel")
    ScriptedChatModel localModel;

    @Test
    void theApplicationsProviderStaysTheChatModelByType() {
        assertThat(byType).isSameAs(localModel);
        assertThat(autoConfiguredBuilder.build().prompt().user("hi").call().content())
                .isEqualTo("answer from the local provider");
    }

    @Test
    @Timeout(60)
    void theFleetModelInjectedByNameIsServedByTheOtherApplication() {
        int localPrompts = localModel.prompts.size();
        ChatResponse response = fleetChat.prompt().user("hello fleet").call().chatResponse();

        assertThat(response.getResult().getOutput().getText()).isEqualTo("answer from the fleet");
        assertThat((String) response.getMetadata().get(FleetChatModel.SERVED_BY_KEY))
                .isEqualTo(server.getBean(PeerIdentity.class).peerId().value());
        assertThat(server.getBean(ScriptedChatModel.class).prompts)
                .anySatisfy(p -> assertThat(p.getInstructions().get(p.getInstructions().size() - 1).getText())
                        .isEqualTo("hello fleet"));
        assertThat(localModel.prompts).as("the local provider was not asked").hasSize(localPrompts);
    }

    @Test
    @Timeout(60)
    void theFleetModelStreamsFromTheOtherApplication() {
        String streamed = fleetChat.prompt().user("stream please").stream().content()
                .collect(Collectors.joining()).block(Duration.ofSeconds(30));
        assertThat(streamed).isEqualTo("streamed from the fleet");
    }
}
