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
import ai.badmonkey.agentspaces.connect.DataQueryClient;
import ai.badmonkey.agentspaces.springai.connect.VectorStoreAssetProvider;
import ai.badmonkey.agentspaces.springai.connect.VectorStoreConnector;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FleetVectorStoreAutoConfigurationTest {

    @Test
    void offByDefaultEvenWithAStore() {
        FleetApps.runner("data").withUserConfiguration(Store.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(VectorStoreAssetProvider.class)
                    .doesNotHaveBean(VectorStoreConnector.class);
        });
    }

    @Test
    @Timeout(60)
    void enabledItServesTheStoreAsAFleetAsset() {
        FleetApps.runner("data").withUserConfiguration(Store.class)
                .withPropertyValues("agentspaces.springai.vector-store.enabled=true",
                        "agentspaces.springai.vector-store.asset=handbook",
                        "agentspaces.springai.vector-store.freshness=2m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(VectorStoreConnector.class).isRunning()).isTrue();
                    VectorStoreAssetProvider provider = context.getBean(VectorStoreAssetProvider.class);

                    // A query through the data space reaches the store and comes back as a
                    // document row (the scripted embedding model does not rank by meaning).
                    DataQueryClient client = new DataQueryClient(
                            context.getBean(AgentSpaces.class).group("test-fleet").space("data"), "asker");
                    Optional<DataQueryClient.Fetched> fetched = client.fetch("handbook",
                            Map.of("query", "what happens when a worker crashes", "topK", "1"),
                            Duration.ofSeconds(20));
                    assertThat(fetched).hasValueSatisfying(f -> assertThat(f.result().rows()).singleElement()
                            .satisfies(row -> assertThat(row).containsKeys("id", "text", "score", "topic")));
                    assertThat(provider.executions()).isEqualTo(1);
                });
    }

    @Test
    void enabledWithoutAStoreFailsWithTheFix() {
        FleetApps.runner("data").withPropertyValues("agentspaces.springai.vector-store.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("needs a VectorStore bean"));
    }

    @Test
    void enabledWithoutItsSpaceFailsNamingTheProperty() {
        FleetApps.runner("work").withUserConfiguration(Store.class)
                .withPropertyValues("agentspaces.springai.vector-store.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("agentspaces.springai.vector-store.space"));
    }

    @Test
    void backsOffWithoutTheVectorStoreModule() {
        FleetApps.runner("data").withClassLoader(new FilteredClassLoader(VectorStore.class))
                .withPropertyValues("agentspaces.springai.vector-store.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(VectorStoreAssetProvider.class)
                            .doesNotHaveBean(VectorStoreConnector.class);
                });
    }

    @Test
    void anApplicationProviderReplacesTheDefault() {
        FleetApps.runner("data").withUserConfiguration(Store.class, OwnProvider.class)
                .withPropertyValues("agentspaces.springai.vector-store.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VectorStoreAssetProvider.class);
                    assertThat(context.getBean(VectorStoreAssetProvider.class))
                            .isSameAs(context.getBean(OwnProvider.class).provider);
                    assertThat(context).hasSingleBean(VectorStoreConnector.class);
                });
    }

    @Test
    void blankAssetNamesAreRefused() {
        FleetApps.runner("data").withUserConfiguration(Store.class)
                .withPropertyValues("agentspaces.springai.vector-store.enabled=true",
                        "agentspaces.springai.vector-store.asset= ")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("agentspaces.springai.vector-store.asset"));
    }

    @Configuration(proxyBeanMethods = false)
    static class Store {
        @Bean
        VectorStore vectorStore() {
            SimpleVectorStore store = SimpleVectorStore.builder(new ScriptedEmbeddingModel()).build();
            store.add(List.of(new Document("Leases make crashed work reappear", Map.of("topic", "leases")),
                    new Document("Gossip spreads state between peers", Map.of("topic", "gossip"))));
            return store;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnProvider {
        final VectorStoreAssetProvider provider;

        OwnProvider(VectorStore store) {
            this.provider = new VectorStoreAssetProvider(store, "own", "An application's own asset",
                    Duration.ofMinutes(1));
        }

        @Bean
        VectorStoreAssetProvider ownProvider() {
            return provider;
        }
    }
}
