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
package ai.badmonkey.agentspaces.springai.connect;

import ai.badmonkey.agentspaces.connect.ConnectorRuntime;
import ai.badmonkey.agentspaces.connect.DataQueryClient;
import ai.badmonkey.agentspaces.springai.support.ScriptedEmbeddingModel;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F8 over TCP: a SimpleVectorStore served as a fleet asset. Two agents on
 * other peers ask it the same question; the store is searched once.
 */
class VectorStoreAssetProviderFlowTest {

    private static final String FOUNDING = "springai-vector-asset-v1";

    @Test
    @Timeout(120)
    void twoAgentsShareOneSearchOfTheKnowledgeBase() throws Exception {
        SimpleVectorStore store = SimpleVectorStore.builder(new ScriptedEmbeddingModel()).build();
        store.add(List.of(new Document("Leases make crashed work reappear", Map.of("topic", "leases")),
                new Document("Gossip spreads state between peers", Map.of("topic", "gossip"))));
        VectorStoreAssetProvider provider = new VectorStoreAssetProvider(store, "handbook",
                "The fleet operations handbook", Duration.ofMinutes(5));
        int seed = TestPeer.freePort();
        try (TestPeer library = TestPeer.start(FOUNDING, "test-fleet", seed, 0, "data");
             TestPeer agentA = TestPeer.start(FOUNDING, "test-fleet", TestPeer.freePort(), seed, "data");
             TestPeer agentB = TestPeer.start(FOUNDING, "test-fleet", TestPeer.freePort(), seed, "data");
             ConnectorRuntime connector = new ConnectorRuntime(library.spaces.get("data"), library.discovery,
                     library.identity, "handbook-connector", provider, library.runtime.id(),
                     InstantSource.system(), Duration.ofMinutes(1))) {
            connector.start();
            Thread.sleep(1500);
            Map<String, String> question = Map.of("query", "what happens when a worker crashes", "topK", "1");

            Optional<DataQueryClient.Fetched> first = new DataQueryClient(agentA.spaces.get("data"), "a")
                    .fetch("handbook", question, Duration.ofSeconds(20));
            assertThat(first).hasValueSatisfying(f -> {
                assertThat(f.fromCache()).isFalse();
                assertThat(f.result().rows()).singleElement()
                        .satisfies(row -> assertThat(row).containsKeys("id", "text", "score", "topic"));
            });

            DataQueryClient clientB = new DataQueryClient(agentB.spaces.get("data"), "b");
            Optional<DataQueryClient.Fetched> second = Optional.empty();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && (second.isEmpty() || !second.get().fromCache())) {
                second = clientB.fetch("handbook", question, Duration.ofSeconds(5));
                Thread.sleep(200);
            }
            assertThat(second).hasValueSatisfying(f -> assertThat(f.fromCache()).isTrue());
            assertThat(provider.executions()).isEqualTo(1);
            assertThat(provider.query("handbook", Map.of()).error()).contains("'query'");
        }
    }
}
