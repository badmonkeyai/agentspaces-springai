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

import ai.badmonkey.agentspaces.springai.model.FleetChatModel;
import ai.badmonkey.agentspaces.springai.model.ModelCatalog;
import ai.badmonkey.agentspaces.springai.model.ModelRequestRouter;
import ai.badmonkey.agentspaces.springai.model.ModelServer;
import ai.badmonkey.agentspaces.springai.model.StreamChunkingPolicy;
import ai.badmonkey.agentspaces.springai.model.StreamRestartPolicy;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class FleetModelAutoConfigurationTest {

    private static ApplicationContextRunner runner() {
        return FleetApps.runner("work", "model-requests")
                .withConfiguration(AutoConfigurations.of(ChatClientAutoConfiguration.class));
    }

    @Test
    void bothSidesAreOffByDefault() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FleetChatModel.class);
            assertThat(context).doesNotHaveBean(ModelServer.class);
        });
    }

    @Test
    void onANodeWithNoProviderTheFleetIsTheChatModel() {
        runner().withPropertyValues("agentspaces.springai.model-client.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(FleetChatModel.class);
            assertThat(context).hasSingleBean(ChatClient.Builder.class);
            assertThat(context).hasSingleBean(StreamRestartPolicy.class);
        });
    }

    @Test
    void besideAProviderTheProviderStaysTheDefaultAndTheFleetIsInjectedByName() {
        runner().withPropertyValues("agentspaces.springai.model-client.enabled=true")
                .withUserConfiguration(Provider.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatModel.class)).isSameAs(Provider.MODEL);
                    assertThat(context.getBean("fleetChatModel")).isInstanceOf(FleetChatModel.class);
                });
    }

    @Test
    void theServerServesTheProviderAndNeverTheFleetModel() {
        runner().withPropertyValues("agentspaces.springai.model-client.enabled=true",
                        "agentspaces.springai.model-server.enabled=true",
                        "agentspaces.springai.model-server.models=m1,m2")
                .withUserConfiguration(Provider.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelCatalog catalog = context.getBean(ModelCatalog.class);
                    assertThat(catalog.models()).containsExactly("m1", "m2");
                    assertThat(catalog.model("m2")).containsSame(Provider.MODEL);
                    assertThat(catalog.model("other")).isEmpty();
                    assertThat(context).hasSingleBean(ModelRequestRouter.class);
                    assertThat(context).hasSingleBean(StreamChunkingPolicy.class);
                    assertThat(context.getBean(ModelServer.class).isRunning()).isTrue();
                });
    }

    @Test
    void misconfigurationsFailAtStartupWithTheFix() {
        runner().withPropertyValues("agentspaces.springai.model-server.enabled=true")
                .withUserConfiguration(Provider.class)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("agentspaces.springai.model-server.models"));
        FleetApps.runner("work").withPropertyValues("agentspaces.springai.model-client.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("- name: model-requests"));
        runner().withPropertyValues("agentspaces.springai.model-server.enabled=true",
                        "agentspaces.springai.model-server.models=m1",
                        "agentspaces.springai.model-server.routing=price")
                .withUserConfiguration(Provider.class)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("strategy: AUCTION"));
    }

    @Configuration(proxyBeanMethods = false)
    static class Provider {
        static final ChatModel MODEL = ScriptedChatModel.answering("provider");

        @Bean
        ChatModel providerChatModel() {
            return MODEL;
        }
    }
}
