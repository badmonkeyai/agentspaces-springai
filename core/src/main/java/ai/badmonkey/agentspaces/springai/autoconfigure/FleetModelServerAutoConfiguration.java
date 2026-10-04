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

import ai.badmonkey.agentspaces.api.spi.Authorizer;
import ai.badmonkey.agentspaces.springai.FleetContext;
import ai.badmonkey.agentspaces.springai.FleetTaskScheduler;
import ai.badmonkey.agentspaces.springai.model.FleetChatModel;
import ai.badmonkey.agentspaces.springai.model.ModelCatalog;
import ai.badmonkey.agentspaces.springai.model.ModelRequestRouter;
import ai.badmonkey.agentspaces.springai.model.ModelServer;
import ai.badmonkey.agentspaces.springai.model.StreamChunkingPolicy;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F4, serving side: a {@link ModelServer} over this peer's provider
 * {@code ChatModel} beans, with its {@link ModelCatalog},
 * {@link ModelRequestRouter}, and {@link StreamChunkingPolicy}, each replaceable
 * by declaring a bean of its interface. The fleet's own {@code FleetChatModel}
 * is never served, so a server cannot loop requests back into the fleet.
 */
@AutoConfiguration(after = {AgentSpacesSpringAiAutoConfiguration.class, FleetModelClientAutoConfiguration.class})
@ConditionalOnClass(ChatModel.class)
@ConditionalOnBean(FleetContext.class)
@ConditionalOnProperty(prefix = "agentspaces.springai.model-server", name = "enabled", havingValue = "true")
public class FleetModelServerAutoConfiguration {

    /**
     * The models this peer serves.
     *
     * @param properties the properties
     * @param models     the application's chat models
     * @return the catalog
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelCatalog modelCatalog(AgentSpacesSpringAiProperties properties, Map<String, ChatModel> models) {
        Map<String, ChatModel> providers = new LinkedHashMap<>();
        models.forEach((name, model) -> {
            if (!(model instanceof FleetChatModel)) {
                providers.put(name, model);
            }
        });
        AgentSpacesSpringAiProperties.ModelServer server = properties.modelServer();
        return ModelCatalog.of(server.models(), providers, server.modelBeans());
    }

    /**
     * Which requests this server takes: by model name, or by auction on price.
     *
     * @param properties the properties
     * @return the router
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelRequestRouter modelRequestRouter(AgentSpacesSpringAiProperties properties) {
        AgentSpacesSpringAiProperties.ModelServer server = properties.modelServer();
        return server.routing() == AgentSpacesSpringAiProperties.Routing.PRICE
                ? ModelRequestRouter.byPrice(server.prices()) : ModelRequestRouter.byModel();
    }

    /**
     * How streamed deltas are batched into chunks.
     *
     * @param properties the properties
     * @return the policy
     */
    @Bean
    @ConditionalOnMissingBean
    public StreamChunkingPolicy streamChunkingPolicy(AgentSpacesSpringAiProperties properties) {
        AgentSpacesSpringAiProperties.ModelServer server = properties.modelServer();
        return StreamChunkingPolicy.of(server.chunkInterval(), server.chunkMaxDeltas());
    }

    /**
     * The server.
     *
     * @param fleet      the fleet context
     * @param catalog    the catalog
     * @param router     the router
     * @param chunking   the chunking policy
     * @param scheduler  the scheduler
     * @param properties the properties
     * @return the server
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelServer modelServer(FleetContext fleet, ModelCatalog catalog, ModelRequestRouter router,
                                   StreamChunkingPolicy chunking, FleetTaskScheduler scheduler,
                                   AgentSpacesSpringAiProperties properties) {
        AgentSpacesSpringAiProperties.ModelServer server = properties.modelServer();
        for (String model : catalog.models()) {
            if (!fleet.authorizer().permits(fleet.peerId(), Authorizer.Operation.MODEL_SERVE, model)) {
                System.getLogger(ModelServer.class.getName()).log(System.Logger.Level.WARNING,
                        "this peer is not permitted MODEL_SERVE for '" + model
                                + "'; callers will ignore its answers (agentspaces.security.grants.model-serve)");
            }
        }
        return new ModelServer(fleet.requiredSpace(server.space(), "agentspaces.springai.model-server.space"),
                catalog, router, chunking, scheduler.scheduler(), fleet.peerId().value(),
                new ModelServer.Settings(server.lease(), server.chunkLease(), server.responseLease(),
                        server.concurrency()));
    }
}
