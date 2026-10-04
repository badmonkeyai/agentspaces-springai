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
package ai.badmonkey.agentspaces.springai.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A deterministic three-dimensional embedding model that counts its calls. */
public class ScriptedEmbeddingModel implements EmbeddingModel {

    public final AtomicInteger calls = new AtomicInteger();

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            embeddings.add(new Embedding(embed(request.getInstructions().get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(String text) {
        calls.incrementAndGet();
        long vowels = text.chars().filter(c -> "aeiou".indexOf(c) >= 0).count();
        return new float[] {text.length(), vowels, 1f};
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return 3;
    }
}
