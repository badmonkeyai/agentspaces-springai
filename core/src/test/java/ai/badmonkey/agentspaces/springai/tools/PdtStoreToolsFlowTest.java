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
package ai.badmonkey.agentspaces.springai.tools;

import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store tools of PDT 2 phase 7, P2-C-04 (srad/agentspaces-pdt2.md §3.2): one Boot peer is a content
 * store ({@code agentspaces.store.enabled}); the other writes a map ref through {@code pdt_assoc}, asks the
 * stores to hold its root through {@code pdt_pin}, and reads the store's receipt through
 * {@code pdt_store_status}, over real sockets. A pin of a root this peer does not hold, and arguments out of
 * range, answer errors the model can read.
 */
class PdtStoreToolsFlowTest {

    private static final String FOUNDING = "springai-pdt-store-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    private static ConfigurableApplicationContext peer(int bindPort, int seed, boolean store) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(App.class)
                .properties(FleetApps.nodeProperties(bindPort, FOUNDING, seed, "work"))
                .properties(Map.of("agentspaces.springai.capability-tools.pdt", "true",
                        "agentspaces.content.enabled", "true",
                        "agentspaces.content.store.backends[0]", "memory",
                        "agentspaces.pdt.enabled", "true",
                        "agentspaces.pdt.store.kind", "memory"));
        if (store) {
            builder.properties(Map.of("agentspaces.store.enabled", "true",
                    "agentspaces.store.name", "site-a",
                    "agentspaces.store.capacity", "64MiB",
                    "agentspaces.store.accept-requesters", "open"));
        }
        return builder.run();
    }

    private static boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        return condition.getAsBoolean();
    }

    @Test
    @Timeout(180)
    void aModelPinsARefsRootOnAStoreAndReadsTheReceipt() throws Exception {
        int seed = TestPeer.freePort();
        try (ConfigurableApplicationContext store = peer(seed, 0, true);
             ConfigurableApplicationContext member = peer(TestPeer.freePort(), seed, false)) {
            String storePeer = store.getBean(PeerIdentity.class).peerId().value();
            FleetCapabilityTools tools = member.getBean(FleetCapabilityTools.class);
            assertThat(FleetCapabilityTools.PDT_TOOLS).contains("pdt_pin", "pdt_store_status");
            assertThat(tools.toolCallbacks().getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                    .containsExactlyInAnyOrderElementsOf(FleetCapabilityTools.PDT_TOOLS);

            String root = JSON.readTree(tools.pdtAssoc("invoices", "inv-1", "\"paid\"")).get("root").asString();
            assertThat(tools.pdtStoreStatus(root)).contains("\"copies\":0");

            String[] pinned = new String[1];
            assertThat(await(() -> !(pinned[0] = tools.pdtPin(root, 1, 2)).contains("error"), Duration.ofSeconds(60)))
                    .as("the store's card reaches the member: %s", pinned[0]).isTrue();
            JsonNode pin = JSON.readTree(pinned[0]);
            assertThat(pin.get("root").asString()).isEqualTo(root);
            assertThat(pin.get("kind").asString()).isEqualTo("tree");
            assertThat(pin.get("copies").asInt()).isEqualTo(1);
            assertThat(pin.get("leaseSeconds").asLong()).isEqualTo(7200);
            assertThat(pin.get("stores").get(0).asString()).isEqualTo(storePeer);

            assertThat(await(() -> tools.pdtStoreStatus(root).contains("\"copies\":1"), Duration.ofSeconds(60)))
                    .as(tools.pdtStoreStatus(root)).isTrue();
            JsonNode status = JSON.readTree(tools.pdtStoreStatus(root));
            JsonNode holder = status.get("holders").get(0);
            assertThat(holder.get("store").asString()).isEqualTo(storePeer);
            assertThat(holder.get("bytes").asLong()).isPositive();
            assertThat(holder.get("until").asString()).endsWith("Z");
            assertThat(holder.get("blind").asBoolean()).isFalse();
            assertThat(status.get("unplaced").asBoolean()).isFalse();

            assertThat(tools.pdtPin("bafyreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku", 1, 1))
                    .contains("error").contains("not complete");
            assertThat(tools.pdtPin(root, 0, 1)).contains("error").contains("copies");
            assertThat(tools.pdtPin(root, 1, 0)).contains("error").contains("hours");
            assertThat(tools.pdtStoreStatus("")).contains("error");
        }
    }
}
