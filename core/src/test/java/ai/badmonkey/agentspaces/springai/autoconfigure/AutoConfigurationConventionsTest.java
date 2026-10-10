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
import ai.badmonkey.agentspaces.capabilities.semantic.HashingEmbedder;
import ai.badmonkey.agentspaces.springai.FleetContext;
import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.RestartMode;
import ai.badmonkey.agentspaces.springai.embed.SpringAiEmbedder;
import ai.badmonkey.agentspaces.springai.mcp.FleetMcpToolSync;
import ai.badmonkey.agentspaces.springai.memory.ConversationIdResolver;
import ai.badmonkey.agentspaces.springai.memory.FleetChatMemoryAdvisor;
import ai.badmonkey.agentspaces.springai.model.FleetChatModel;
import ai.badmonkey.agentspaces.springai.model.ModelCatalog;
import ai.badmonkey.agentspaces.springai.model.ModelRequestRouter;
import ai.badmonkey.agentspaces.springai.model.ModelServer;
import ai.badmonkey.agentspaces.springai.model.StreamChunkingPolicy;
import ai.badmonkey.agentspaces.springai.model.StreamRestartPolicy;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.springai.support.ScriptedEmbeddingModel;
import ai.badmonkey.agentspaces.springai.tools.FleetCapabilityTools;
import ai.badmonkey.agentspaces.springai.tools.FleetDiscoveryTools;
import ai.badmonkey.agentspaces.springai.tools.FleetTools;
import ai.badmonkey.agentspaces.springai.usage.FleetUsageObservationHandler;
import ai.badmonkey.agentspaces.springai.worker.TakeContextAccessor;
import ai.badmonkey.agentspaces.springai.worker.TakeLeaseAdvisor;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The Spring conventions every feature's auto-configuration keeps (design
 * section 7.1): it backs off when the classes it needs are absent, even when
 * its property turns it on, and each extension point yields to a bean the
 * application supplies. The per-feature tests cover the property switches.
 */
class AutoConfigurationConventionsTest {

    @Configuration(proxyBeanMethods = false)
    static class Provider {
        @Bean
        ChatModel providerChatModel() {
            return ScriptedChatModel.answering("provider");
        }
    }

    // F1: fleet tools

    @Test
    void fleetToolsBackOffWithoutSpringAisToolApi() {
        FleetApps.runner().withClassLoader(new FilteredClassLoader(ToolCallbackProvider.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FleetContext.class);
                    assertThat(context).doesNotHaveBean(FleetTools.class);
                });
    }

    // F2: discovery and data tools

    @Test
    void discoveryToolsBackOffWithoutSpringAisToolApi() {
        FleetApps.runner().withClassLoader(new FilteredClassLoader(ToolCallbackProvider.class))
                .withPropertyValues("agentspaces.springai.discovery-tools.agents=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FleetDiscoveryTools.class);
                });
    }

    @Test
    void eachToolSwitchesOnAlone() {
        FleetApps.runner().withPropertyValues("agentspaces.springai.discovery-tools.assets=true")
                .run(context -> assertThat(context.getBean(FleetDiscoveryTools.class).toolCallbacks()
                        .getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                        .containsExactly("list_fleet_assets"));
        FleetApps.runner("work", "data").withPropertyValues("agentspaces.springai.discovery-tools.data=true")
                .run(context -> assertThat(context.getBean(FleetDiscoveryTools.class).toolCallbacks()
                        .getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                        .containsExactly("fetch_fleet_data"));
    }

    @Test
    void anApplicationsToolsReplaceTheDefault() {
        FleetDiscoveryTools own = new FleetDiscoveryTools(null, null, List::of, new JsonMapper(), 100, Set.of());
        FleetApps.runner().withBean("ownDiscoveryTools", FleetDiscoveryTools.class, () -> own)
                .withPropertyValues("agentspaces.springai.discovery-tools.agents=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FleetDiscoveryTools.class)).isSameAs(own);
                });
    }

    // F9: capability tools

    @Test
    void capabilityToolsBackOffWithoutSpringAisToolApi() {
        FleetApps.runner().withClassLoader(new FilteredClassLoader(ToolCallbackProvider.class))
                .withPropertyValues("agentspaces.springai.capability-tools.vote=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FleetCapabilityTools.class);
                });
    }

    @Test
    void anApplicationsCapabilityToolsReplaceTheDefault() {
        FleetCapabilityTools own = new FleetCapabilityTools(() -> null, () -> null, Duration.ofHours(1),
                Duration.ofSeconds(1), new JsonMapper(), Set.of());
        FleetApps.runner().withBean("ownCapabilityTools", FleetCapabilityTools.class, () -> own)
                .withPropertyValues("agentspaces.springai.capability-tools.aggregate=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FleetCapabilityTools.class)).isSameAs(own);
                });
    }

    // F3: the take-lease advisor and chat memory

    @Test
    void backOffWithoutSpringAisChatClient() {
        FleetApps.runner("work", "conversations").withClassLoader(new FilteredClassLoader(ChatClient.class))
                .withPropertyValues("agentspaces.springai.memory.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TakeLeaseAdvisor.class)
                            .doesNotHaveBean(TakeContextAccessor.class)
                            .doesNotHaveBean(FleetChatMemoryAdvisor.class);
                });
    }

    @Test
    void anApplicationsAdvisorAndAccessorReplaceTheDefaults() {
        TakeContextAccessor accessor = new TakeContextAccessor();
        FleetApps.runner()
                .withBean("ownAdvisor", TakeLeaseAdvisor.class, () -> new TakeLeaseAdvisor(
                        new org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler(),
                        Duration.ofSeconds(5)))
                .withBean("ownAccessor", TakeContextAccessor.class, () -> accessor)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TakeLeaseAdvisor.class);
                    assertThat(context).getBean("ownAdvisor").isSameAs(context.getBean(TakeLeaseAdvisor.class));
                    assertThat(context.getBean(TakeContextAccessor.class)).isSameAs(accessor);
                });
    }

    @Test
    void anApplicationsMemoryAdvisorReplacesTheDefault() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
        FleetChatMemoryAdvisor own = new FleetChatMemoryAdvisor(memory,
                ConversationIdResolver.of(AgentSpacesSpringAiProperties.ConversationIdMode.EXPLICIT));
        FleetApps.runner("work", "conversations").withPropertyValues("agentspaces.springai.memory.enabled=true")
                .withBean("ownMemoryAdvisor", FleetChatMemoryAdvisor.class, () -> own)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FleetChatMemoryAdvisor.class)).isSameAs(own);
                });
    }

    // F4: model access

    private ApplicationContextRunner bothSides() {
        return FleetApps.runner("work", "model-requests").withUserConfiguration(Provider.class)
                .withPropertyValues("agentspaces.springai.model-client.enabled=true",
                        "agentspaces.springai.model-server.enabled=true",
                        "agentspaces.springai.model-server.models=m1");
    }

    @Test
    void bothSidesBackOffWithoutSpringAisChatModel() {
        FleetApps.runner("work", "model-requests").withClassLoader(new FilteredClassLoader(ChatModel.class))
                .withPropertyValues("agentspaces.springai.model-client.enabled=true",
                        "agentspaces.springai.model-server.enabled=true",
                        "agentspaces.springai.model-server.models=m1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FleetChatModel.class)
                            .doesNotHaveBean(ModelServer.class)
                            .doesNotHaveBean(ModelCatalog.class);
                });
    }

    @Test
    void anApplicationsPoliciesCatalogAndRouterReplaceTheDefaults() {
        StreamRestartPolicy restart = StreamRestartPolicy.of(RestartMode.RESTART);
        ModelCatalog catalog = ModelCatalog.of(List.of("custom"),
                Map.of("x", ScriptedChatModel.answering("custom")), Map.of());
        ModelRequestRouter router = ModelRequestRouter.byModel();
        StreamChunkingPolicy chunking = StreamChunkingPolicy.of(Duration.ofMillis(5), 1);
        bothSides()
                .withBean("ownRestart", StreamRestartPolicy.class, () -> restart)
                .withBean("ownCatalog", ModelCatalog.class, () -> catalog)
                .withBean("ownRouter", ModelRequestRouter.class, () -> router)
                .withBean("ownChunking", StreamChunkingPolicy.class, () -> chunking)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StreamRestartPolicy.class)).isSameAs(restart);
                    assertThat(context.getBean(ModelCatalog.class)).isSameAs(catalog);
                    assertThat(context.getBean(ModelRequestRouter.class)).isSameAs(router);
                    assertThat(context.getBean(StreamChunkingPolicy.class)).isSameAs(chunking);
                    assertThat(context.getBean(ModelServer.class).isRunning())
                            .as("the default server runs on the application's catalog").isTrue();
                });
    }

    @Test
    void anApplicationsFleetModelAndServerReplaceTheDefaults() {
        FleetChatModel fleetModel = mock(FleetChatModel.class);
        ModelServer server = mock(ModelServer.class);
        bothSides()
                .withBean("ownFleetModel", FleetChatModel.class, () -> fleetModel)
                .withBean("ownServer", ModelServer.class, () -> server)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FleetChatModel.class)).isSameAs(fleetModel);
                    assertThat(context.getBean(ModelServer.class)).isSameAs(server);
                    assertThat(context).doesNotHaveBean("fleetChatModel").doesNotHaveBean("modelServer");
                });
    }

    // F5: embeddings

    @Test
    void backsOffWithoutSpringAisEmbeddingModel() {
        FleetApps.runner().withClassLoader(new FilteredClassLoader(EmbeddingModel.class))
                .withPropertyValues("agentspaces.springai.embedder.type=spring-ai")
                .run(context -> {
                    assertThat(context).as("no fail-fast: the feature is absent, not misconfigured")
                            .hasNotFailed();
                    assertThat(context).doesNotHaveBean(SpringAiEmbedder.class);
                    assertThat(context.getBean(Embedder.class)).isInstanceOf(HashingEmbedder.class);
                });
    }

    @Test
    void anApplicationsEmbedderWinsEvenWithSpringAiSelected() {
        Embedder own = new HashingEmbedder(64);
        FleetApps.runner().withBean(EmbeddingModel.class, ScriptedEmbeddingModel::new)
                .withBean("ownEmbedder", Embedder.class, () -> own)
                .withPropertyValues("agentspaces.springai.embedder.type=spring-ai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SpringAiEmbedder.class);
                    assertThat(context.getBean(Embedder.class)).isSameAs(own);
                });
    }

    // F6: fleet-wide usage

    @Test
    void backsOffWithoutSpringAisChatObservations() {
        FleetApps.runner().withClassLoader(new FilteredClassLoader(ChatModelObservationContext.class))
                .withPropertyValues("agentspaces.springai.usage.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FleetUsageObservationHandler.class);
                });
    }

    @Test
    void anApplicationsHandlerReplacesTheDefault() {
        FleetUsageObservationHandler own = new FleetUsageObservationHandler(usage -> { }, "peer:own");
        FleetApps.runner().withPropertyValues("agentspaces.springai.usage.enabled=true")
                .withBean("ownHandler", FleetUsageObservationHandler.class, () -> own)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FleetUsageObservationHandler.class)).isSameAs(own);
                });
    }

    // F7: MCP export

    private ApplicationContextRunner withServer() {
        return FleetApps.runner().withBean(McpSyncServer.class, () -> mock(McpSyncServer.class))
                .withPropertyValues("agentspaces.springai.mcp.enabled=true",
                        "agentspaces.springai.mcp.include-agents=summarizer");
    }

    @Test
    void onWithAnMcpServerItSyncsTheFleetTools() {
        withServer().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FleetMcpToolSync.class);
            assertThat(context.getBean(FleetMcpToolSync.class).isRunning()).isTrue();
        });
    }

    @Test
    void backsOffWithoutTheMcpServerModule() {
        withServer().withClassLoader(new FilteredClassLoader(McpSyncServer.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FleetMcpToolSync.class);
        });
    }

    @Test
    void anApplicationsSyncReplacesTheDefault() {
        withServer().withBean("ownSync", FleetMcpToolSync.class, () -> mock(FleetMcpToolSync.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FleetMcpToolSync.class);
                    assertThat(context).hasBean("ownSync").doesNotHaveBean("fleetMcpToolSync");
                });
    }
}
