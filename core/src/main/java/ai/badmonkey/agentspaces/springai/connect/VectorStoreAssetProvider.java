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

import ai.badmonkey.agentspaces.api.ad.AssetCard;
import ai.badmonkey.agentspaces.common.id.GroupId;
import ai.badmonkey.agentspaces.common.id.PeerId;
import ai.badmonkey.agentspaces.connect.AssetProvider;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Spring AI {@link VectorStore} as a fleet data asset. Serve it with the
 * connector SDK's {@code ConnectorRuntime}, and any agent in the fleet queries
 * the shared knowledge base through {@code DataQueryClient} or the
 * {@code fetch_fleet_data} tool, with leased, pull-once results: an identical
 * question asked within the freshness window reads the leased answer from the
 * space instead of searching again.
 *
 * <p>Query parameters: {@code query} (the text, required), {@code topK}
 * (default 4), {@code threshold} (similarity, default none), and
 * {@code filter} (a Spring AI filter expression). Each result row carries the
 * document's {@code id}, {@code text}, {@code score}, and its metadata as
 * strings.
 */
public class VectorStoreAssetProvider implements AssetProvider {

    private final VectorStore store;
    private final String asset;
    private final String description;
    private final Duration freshness;
    private final AtomicLong executions = new AtomicLong();

    /**
     * Creates the provider.
     *
     * @param store       the vector store
     * @param asset       the asset's name
     * @param description what the store holds, for discovery by meaning
     * @param freshness   how long an answer stays valid in the space
     */
    public VectorStoreAssetProvider(VectorStore store, String asset, String description, Duration freshness) {
        this.store = Objects.requireNonNull(store, "store");
        this.asset = Objects.requireNonNull(asset, "asset");
        this.description = Objects.requireNonNull(description, "description");
        this.freshness = Objects.requireNonNull(freshness, "freshness");
    }

    @Override
    public List<AssetCard> describeAssets(GroupId group, PeerId issuer, Instant now) {
        return List.of(new AssetCard("aspace://" + group.value() + "/asset/" + asset, issuer, group, now,
                Duration.ofMinutes(15), asset, "vectorstore://" + store.getName() + "/" + asset, description,
                "document(id,text,score,metadata)", freshness.toString(),
                Map.of("kind", "vector-store"),
                Map.of("mode", "read-only", "query", "aspace:cap/data-query",
                        "parameters", "query,topK,threshold,filter")));
    }

    @Override
    public QueryResult query(String asset, Map<String, String> parameters) {
        if (!this.asset.equals(asset)) {
            return QueryResult.failed("unknown asset '" + asset + "'");
        }
        String text = parameters.get("query");
        if (text == null || text.isBlank()) {
            return QueryResult.failed("the 'query' parameter is required");
        }
        try {
            SearchRequest.Builder request = SearchRequest.builder().query(text)
                    .topK(Integer.parseInt(parameters.getOrDefault("topK", "4")));
            if (parameters.containsKey("threshold")) {
                request.similarityThreshold(Double.parseDouble(parameters.get("threshold")));
            }
            if (parameters.containsKey("filter")) {
                request.filterExpression(parameters.get("filter"));
            }
            executions.incrementAndGet();
            List<Map<String, String>> rows = new ArrayList<>();
            for (Document document : store.similaritySearch(request.build())) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", document.getId());
                row.put("text", document.getText() == null ? "" : document.getText());
                row.put("score", document.getScore() == null ? "" : String.valueOf(document.getScore()));
                document.getMetadata().forEach((key, value) -> row.putIfAbsent(key, String.valueOf(value)));
                rows.add(row);
            }
            return QueryResult.of(rows);
        } catch (RuntimeException e) {
            return QueryResult.failed(e.getMessage());
        }
    }

    /** How many searches ran against the store. */
    public long executions() {
        return executions.get();
    }
}
