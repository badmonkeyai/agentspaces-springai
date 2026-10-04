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

import ai.badmonkey.agentspaces.agent.AgentSpaces;
import ai.badmonkey.agentspaces.api.spi.Embedder;
import ai.badmonkey.agentspaces.capabilities.semantic.HashingEmbedder;
import ai.badmonkey.agentspaces.capabilities.semantic.SemanticDiscovery;
import ai.badmonkey.agentspaces.springai.embed.SpringAiEmbedder;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class FleetEmbeddingAutoConfigurationTest {

    private static SemanticDiscovery semantic(ApplicationContext context) {
        return (SemanticDiscovery) context.getBean(AgentSpaces.class).group("test-fleet")
                .provider(SemanticDiscovery.TYPE).orElseThrow();
    }

    @Test
    void theHashingEmbedderIsTheDefault() {
        FleetApps.runner().withUserConfiguration(Model.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Embedder.class)).isInstanceOf(HashingEmbedder.class);
            assertThat(semantic(context).describe(context.getBean(AgentSpaces.class).group("test-fleet").id())
                    .parameters()).containsEntry("embedder", "hashing");
            assertThat(semantic(context).requiresMatchingEmbedder()).isTrue();
        });
    }

    @Test
    void springAiRanksWithTheEmbeddingModelAndAdvertisesIt() {
        FleetApps.runner().withUserConfiguration(Model.class)
                .withPropertyValues("agentspaces.springai.embedder.type=spring-ai",
                        "agentspaces.springai.embedder.model=text-embedding-3-small",
                        "agentspaces.springai.embedder.require-match=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Embedder.class)).isInstanceOf(SpringAiEmbedder.class);
                    SemanticDiscovery semantic = semantic(context);
                    assertThat(semantic.embedder()).isSameAs(context.getBean(Embedder.class));
                    assertThat(semantic.describe(context.getBean(AgentSpaces.class).group("test-fleet").id())
                            .parameters()).containsEntry("embedder", "spring-ai:text-embedding-3-small")
                            .containsEntry("dimensions", "3");
                    semantic.query("customer orders", 3); // ranks through the model without failing
                    assertThat(semantic.requiresMatchingEmbedder()).as("require-match=false applied").isFalse();
                });
    }

    @Test
    void springAiWithoutAnEmbeddingModelFailsWithTheFix() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.embedder.type=spring-ai")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("needs an EmbeddingModel bean"));
    }

    @Configuration(proxyBeanMethods = false)
    static class Model {
        @Bean
        EmbeddingModel embeddingModel() {
            return new ScriptedEmbeddingModel();
        }
    }
}
