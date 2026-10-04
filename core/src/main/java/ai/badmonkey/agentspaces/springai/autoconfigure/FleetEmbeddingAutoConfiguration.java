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

import ai.badmonkey.agentspaces.api.spi.Embedder;
import ai.badmonkey.agentspaces.springai.embed.SpringAiEmbedder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * F5: with {@code agentspaces.springai.embedder.type=spring-ai}, semantic
 * discovery ranks with the application's Spring AI {@link EmbeddingModel}.
 * Ordered before the AgentSpaces starter, whose default hashing embedder then
 * backs off.
 */
@AutoConfiguration(beforeName = "ai.badmonkey.agentspaces.spring.AgentSpacesAutoConfiguration")
@ConditionalOnClass(EmbeddingModel.class)
@ConditionalOnProperty(prefix = "agentspaces.springai.embedder", name = "type", havingValue = "spring-ai")
@EnableConfigurationProperties(AgentSpacesSpringAiProperties.class)
public class FleetEmbeddingAutoConfiguration {

    /**
     * The embedder semantic discovery ranks with.
     *
     * @param models     the application's embedding model
     * @param properties the properties
     * @return the embedder
     */
    @Bean
    @ConditionalOnMissingBean(Embedder.class)
    public SpringAiEmbedder springAiEmbedder(ObjectProvider<EmbeddingModel> models,
                                             AgentSpacesSpringAiProperties properties) {
        EmbeddingModel model = models.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("agentspaces.springai.embedder.type=spring-ai needs an"
                    + " EmbeddingModel bean; add a Spring AI embedding starter or set the type to hashing");
        }
        AgentSpacesSpringAiProperties.Embedder embedder = properties.embedder();
        return new SpringAiEmbedder(model, embedder.model(), embedder.cacheSize());
    }
}
