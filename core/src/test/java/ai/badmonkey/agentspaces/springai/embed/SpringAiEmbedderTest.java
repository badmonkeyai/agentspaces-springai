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

import ai.badmonkey.agentspaces.springai.support.ScriptedEmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SpringAiEmbedderTest {

    @Test
    void vectorsAreConvertedNormalizedAndCachedByContent() {
        ScriptedEmbeddingModel model = new ScriptedEmbeddingModel();
        SpringAiEmbedder embedder = new SpringAiEmbedder(model, "text-embedding-3-small", 16);

        double[] first = embedder.embed("orders");
        double norm = 0;
        for (double v : first) {
            norm += v * v;
        }
        assertThat(norm).isCloseTo(1.0, within(1e-9));
        assertThat(first[0] / first[2]).isCloseTo(6.0, within(1e-9)); // proportions kept
        assertThat(embedder.dimensions()).isEqualTo(3);
        assertThat(embedder.identity()).isEqualTo("spring-ai:text-embedding-3-small");
        assertThat(embedder.normalized()).isTrue();

        embedder.embed("orders");
        embedder.embed("orders");
        assertThat(model.calls).as("identical text is embedded once").hasValue(1);
        first[0] = 99; // callers get copies
        assertThat(embedder.embed("orders")[0]).isNotEqualTo(99);
    }

    @Test
    void theCacheIsBoundedAndTheIdentityFallsBackToTheModelClass() {
        ScriptedEmbeddingModel model = new ScriptedEmbeddingModel();
        SpringAiEmbedder embedder = new SpringAiEmbedder(model, "", 2);
        embedder.embed("a");
        embedder.embed("b");
        embedder.embed("c");
        assertThat(embedder.cached()).isEqualTo(2);
        assertThat(embedder.identity()).isEqualTo("spring-ai:ScriptedEmbeddingModel");
    }
}
