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
import ai.badmonkey.agentspaces.connect.ConnectorRuntime;
import ai.badmonkey.agentspaces.springai.FleetContext;
import ai.badmonkey.agentspaces.springai.connect.VectorStoreAssetProvider;
import ai.badmonkey.agentspaces.springai.connect.VectorStoreConnector;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * F8: with {@code agentspaces.springai.vector-store.enabled=true}, the
 * application's Spring AI {@link VectorStore} becomes a fleet data asset that
 * any agent queries through {@code DataQueryClient} or the
 * {@code fetch_fleet_data} tool, with leased, pull-once results. Off by
 * default, since it widens what the fleet can read.
 *
 * <p>The store is looked up when the provider is built rather than required by
 * a condition, because a vector store's own auto-configuration may run after
 * this one; enabling the feature without a store fails at startup.
 */
@AutoConfiguration(after = AgentSpacesSpringAiAutoConfiguration.class)
@ConditionalOnClass(VectorStore.class)
@ConditionalOnBean(FleetContext.class)
@ConditionalOnProperty(prefix = "agentspaces.springai.vector-store", name = "enabled", havingValue = "true")
public class FleetVectorStoreAutoConfiguration {

    /**
     * The asset provider over the application's vector store.
     *
     * @param stores     the application's vector store
     * @param properties the properties
     * @return the provider
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorStoreAssetProvider vectorStoreAssetProvider(ObjectProvider<VectorStore> stores,
                                                             AgentSpacesSpringAiProperties properties) {
        VectorStore store = stores.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("agentspaces.springai.vector-store.enabled=true needs a"
                    + " VectorStore bean; add a Spring AI vector store starter or define one");
        }
        AgentSpacesSpringAiProperties.VectorStore config = properties.vectorStore();
        return new VectorStoreAssetProvider(store, config.asset(), config.description(), config.freshness());
    }

    /**
     * Serves the provider through the connector SDK.
     *
     * @param fleet      the fleet context
     * @param provider   the asset provider
     * @param properties the properties
     * @return the connector
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorStoreConnector vectorStoreConnector(FleetContext fleet, VectorStoreAssetProvider provider,
                                                     AgentSpacesSpringAiProperties properties) {
        AgentSpacesSpringAiProperties.VectorStore config = properties.vectorStore();
        // Resolve the space now, so a missing one fails at startup with the property's name.
        var space = fleet.requiredSpace(config.space(), "agentspaces.springai.vector-store.space");
        return new VectorStoreConnector(() -> {
            AgentSpaces.GroupContext group = fleet.group();
            return new ConnectorRuntime(space, group.discovery(), fleet.identity(),
                    config.asset() + "-connector", provider, group.id(), group.clock(), config.freshness())
                    .authorizer(fleet.authorizer());
        });
    }
}
