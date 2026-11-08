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

import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The persistent-tree tools of F9 (srad/agentspaces-pdt.md PD-C-09): one Boot peer puts values
 * into a map ref through {@code pdt_assoc}, the other reads them by path through
 * {@code pdt_read} and lists the versions through {@code pdt_history}; a peer with the family on
 * but the trees off answers an error naming the property.
 */
class PdtToolsFlowTest {

    private static final String FOUNDING = "springai-pdt-flow-v1";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    private static ConfigurableApplicationContext peer(int bindPort, int seed, boolean trees) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(App.class)
                .properties(FleetApps.nodeProperties(bindPort, FOUNDING, seed, "work"))
                .properties(Map.of("agentspaces.springai.capability-tools.pdt", "true"));
        if (trees) {
            builder.properties(Map.of("agentspaces.content.enabled", "true",
                    "agentspaces.content.store.backends[0]", "memory",
                    "agentspaces.pdt.enabled", "true",
                    "agentspaces.pdt.store.kind", "memory"));
        }
        return builder.run();
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    @Timeout(120)
    void aModelAssocsReadsByPathAndListsTheHistory() throws Exception {
        int seed = TestPeer.freePort();
        try (ConfigurableApplicationContext a = peer(seed, 0, true);
             ConfigurableApplicationContext b = peer(TestPeer.freePort(), seed, true)) {
            FleetCapabilityTools toolsA = a.getBean(FleetCapabilityTools.class);
            FleetCapabilityTools toolsB = b.getBean(FleetCapabilityTools.class);
            assertThat(toolsA.toolCallbacks().getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                    .containsExactlyInAnyOrderElementsOf(FleetCapabilityTools.PDT_TOOLS);
            assertThat(FleetCapabilityTools.pathOf("a/b/2/c")).containsExactly("a", "b", 2, "c");

            assertThat(toolsA.pdtAssoc("facts", "total", "3")).contains("\"value\":3").contains("\"count\":1");
            assertThat(toolsA.pdtAssoc("facts", "owner/name", "\"ana\"")).contains("\"count\":2");
            assertThat(toolsA.pdtAssoc("facts", "note", "plain words")).contains("\"value\":\"plain words\"");
            assertThat(toolsA.pdtAssoc("facts", "tags", "[\"x\",\"y\"]")).contains("\"root\":\"bafyrei");

            await(() -> toolsB.pdtRead("facts", "owner/name").contains("\"value\":\"ana\""), Duration.ofSeconds(60));
            assertThat(toolsB.pdtRead("facts", "total")).contains("\"found\":true").contains("\"value\":3");
            assertThat(toolsB.pdtRead("facts", "tags/1")).contains("\"value\":\"y\"");
            assertThat(toolsB.pdtRead("facts", "nothing/here")).contains("\"found\":false");
            assertThat(toolsB.pdtRead("facts", "")).contains("\"count\":4").contains("\"truncated\":false");

            assertThat(toolsB.pdtAssoc("facts", "owner/name", "\"bo\"")).contains("\"root\":\"bafyrei");
            await(() -> toolsA.pdtRead("facts", "owner/name").contains("\"value\":\"bo\""), Duration.ofSeconds(60));
            assertThat(toolsA.pdtHistory("facts", 10)).contains("\"heads\":1").contains("\"head\":true")
                    .contains("\"head\":false").contains("\"seq\":5");
            assertThat(toolsA.pdtAssoc("facts", "", "1")).contains("error");
            assertThat(toolsA.pdtRead("", "x")).contains("error");
        }
    }

    @Test
    @Timeout(60)
    void aPeerWithoutTheTreesNamesTheProperty() {
        try (ConfigurableApplicationContext plain = peer(TestPeer.freePort(), 0, false)) {
            FleetCapabilityTools tools = plain.getBean(FleetCapabilityTools.class);
            assertThat(tools.enabled("pdt_read")).isTrue();
            assertThat(tools.pdtRead("facts", "")).contains("error").contains("agentspaces.pdt.enabled");
            assertThat(FleetCapabilityTools.enabled(false, false, false, true)).containsExactlyElementsOf(FleetCapabilityTools.PDT_TOOLS);
        }
    }
}
