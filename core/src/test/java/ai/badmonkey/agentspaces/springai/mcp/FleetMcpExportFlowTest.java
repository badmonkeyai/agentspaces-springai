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
package ai.badmonkey.agentspaces.springai.mcp;

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.api.ad.AgentCard;
import ai.badmonkey.agentspaces.identity.AdvertisementSigner;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F7 over real MCP: a Spring web application runs Spring AI's MCP server with
 * the fleet export on; an MCP client over Streamable HTTP lists only the fleet
 * agents the deployment named, calls one (answered by a worker on another
 * peer), and hears {@code list_changed} when an included card arrives and lapses.
 */
class FleetMcpExportFlowTest {

    private static final String FOUNDING = "springai-mcp-export-v1";

    public record Brief(String topic) {
    }

    public record Summary(String topic, String text) {
    }

    /** The secret agent's own request type: tuple spaces route by type. */
    public record SecretRequest(String topic) {
    }

    @AgentSpec(name = "summarizer", description = "Summarizes briefs", goals = {"summarize"})
    public static class Summarizer {
        @SpaceTake(space = "work", pollTimeout = "PT0.2S")
        public Summary summarize(Brief brief) {
            return new Summary(brief.topic(), "summary of " + brief.topic());
        }
    }

    @AgentSpec(name = "secret", description = "Must never be exposed over MCP")
    public static class Secret {
        @SpaceTake(space = "work", pollTimeout = "PT0.2S")
        public Summary read(SecretRequest request) {
            return new Summary(request.topic(), "secret");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class McpApp {
    }

    @Test
    @Timeout(120)
    void anMcpClientSeesOnlyTheNamedFleetAgentsAndHearsTheListChange() throws Exception {
        int seed = TestPeer.freePort();
        try (TestPeer worker = TestPeer.start(FOUNDING, "test-fleet", seed, 0, "work");
             ConfigurableApplicationContext app = new SpringApplicationBuilder(McpApp.class)
                     .properties(FleetApps.nodeProperties(FOUNDING, seed, "work"))
                     .properties(Map.of(
                             "spring.main.web-application-type", "servlet",
                             "server.port", "0",
                             "spring.ai.mcp.server.enabled", "true",
                             "spring.ai.mcp.server.protocol", "STREAMABLE",
                             "agentspaces.springai.tools.refresh-interval", "500ms",
                             "agentspaces.springai.mcp.enabled", "true",
                             "agentspaces.springai.mcp.include-agents", "summarizer,ephemeral"))
                     .run()) {
            worker.binder.bind(new Summarizer());
            worker.binder.bind(new Secret());
            FleetMcpToolSync sync = app.getBean(FleetMcpToolSync.class);
            await(() -> sync.published().contains("fleet_summarizer_Brief"), Duration.ofSeconds(60));

            String url = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
            List<List<String>> changes = new CopyOnWriteArrayList<>();
            try (McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder(url)
                            .endpoint("/mcp").build())
                    .requestTimeout(Duration.ofSeconds(30))
                    .toolsChangeConsumer(tools -> changes.add(tools.stream().map(McpSchema.Tool::name).toList()))
                    .build()) {
                client.initialize();
                List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
                assertThat(names).contains("fleet_summarizer_Brief")
                        .as("nothing is exported implicitly").doesNotContain("fleet_secret_SecretRequest");

                McpSchema.CallToolResult result = client.callTool(
                        new McpSchema.CallToolRequest("fleet_summarizer_Brief", Map.of("topic", "mcp")));
                assertThat(result.content()).singleElement().satisfies(content ->
                        assertThat(((McpSchema.TextContent) content).text()).contains("summary of mcp"));

                AgentCard ephemeral = new AgentCard(
                        "aspace://" + worker.runtime.id().value() + "/agent/ephemeral",
                        worker.identity.peerId(), worker.runtime.id(), Instant.now(), Duration.ofSeconds(4),
                        worker.identity.agent("ephemeral"), "Briefly available", List.of(),
                        List.of(Brief.class.getName() + "#v1"), List.of(Summary.class.getName() + "#v1"), Map.of());
                worker.discovery.publish(new AdvertisementSigner().sign(ephemeral, worker.identity));
                await(() -> changes.stream().anyMatch(c -> c.contains("fleet_ephemeral_Brief")), Duration.ofSeconds(30));
                await(() -> !changes.isEmpty() && !changes.get(changes.size() - 1).contains("fleet_ephemeral_Brief"),
                        Duration.ofSeconds(30));
                assertThat(client.listTools().tools().stream().map(McpSchema.Tool::name).toList())
                        .contains("fleet_summarizer_Brief").doesNotContain("fleet_ephemeral_Brief", "fleet_secret_SecretRequest");
            }
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(200);
        }
    }
}
