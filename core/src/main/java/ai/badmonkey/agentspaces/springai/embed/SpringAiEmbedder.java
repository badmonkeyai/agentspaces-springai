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
package ai.badmonkey.agentspaces.springai.embed;

import ai.badmonkey.agentspaces.api.spi.Embedder;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Semantic discovery over a Spring AI {@link EmbeddingModel}: the model's
 * {@code float[]} vectors become the {@code double[]} the {@link Embedder} SPI
 * expects, L2-normalized. Semantic discovery re-embeds every advertisement on
 * each query, so embeddings are cached by the text's content hash; a card's
 * text rarely changes, so the model is called once per card.
 *
 * <p>The identity ({@code spring-ai:<model>}) is advertised on the
 * semantic-discovery capability, and peers query only peers that rank with the
 * same one.
 */
public class SpringAiEmbedder implements Embedder {

    private final EmbeddingModel model;
    private final String identity;
    private final Map<String, double[]> cache;
    private volatile int dimensions = -1;

    /**
     * Creates the embedder.
     *
     * @param model     the embedding model
     * @param modelName the model's name, for the identity; empty for the model's class name
     * @param cacheSize how many embeddings to cache; 0 disables the cache
     */
    public SpringAiEmbedder(EmbeddingModel model, String modelName, int cacheSize) {
        this.model = Objects.requireNonNull(model, "model");
        this.identity = "spring-ai:" + (modelName == null || modelName.isBlank()
                ? model.getClass().getSimpleName() : modelName);
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                return size() > cacheSize;
            }
        };
    }

    @Override
    public double[] embed(String text) {
        String key = hash(text);
        synchronized (cache) {
            double[] cached = cache.get(key);
            if (cached != null) {
                return cached.clone();
            }
        }
        float[] raw = model.embed(text);
        double[] vector = new double[raw.length];
        double norm = 0;
        for (int i = 0; i < raw.length; i++) {
            vector[i] = raw[i];
            norm += vector[i] * vector[i];
        }
        if (norm > 0) {
            double scale = 1.0 / Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        dimensions = vector.length;
        synchronized (cache) {
            cache.put(key, vector);
        }
        return vector.clone();
    }

    @Override
    public int dimensions() {
        int known = dimensions;
        if (known < 0) {
            known = model.dimensions();
            dimensions = known;
        }
        return known;
    }

    @Override
    public String identity() {
        return identity;
    }

    @Override
    public boolean normalized() {
        return true;
    }

    /** How many embeddings are cached. */
    public int cached() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
