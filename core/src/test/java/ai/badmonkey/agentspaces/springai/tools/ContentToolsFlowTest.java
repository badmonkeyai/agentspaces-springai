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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The content swarm tools of F9 (srad/large-networks-bulk-data.md §7.2): one Boot peer
 * publishes a file through {@code publish_content}, the other fetches it by root, reads its
 * text, and lists and describes what the group announced.
 */
class ContentToolsFlowTest {

    private static final String FOUNDING = "springai-content-flow-v1";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    private static ConfigurableApplicationContext peer(int bindPort, int seed) {
        return new SpringApplicationBuilder(App.class)
                .properties(FleetApps.nodeProperties(bindPort, FOUNDING, seed, "work"))
                .properties(Map.of("agentspaces.springai.capability-tools.content", "true",
                        "agentspaces.springai.capability-tools.fetch-timeout", "60s",
                        "agentspaces.content.enabled", "true",
                        "agentspaces.content.piece-size", "65536",
                        "agentspaces.content.store.backends[0]", "memory"))
                .run();
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as(name + " in " + json).isTrue();
        return m.group(1);
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
    void aModelPublishesFetchesReadsAndListsArtifacts(@TempDir Path dir) throws Exception {
        int seed = TestPeer.freePort();
        try (ConfigurableApplicationContext a = peer(seed, 0);
             ConfigurableApplicationContext b = peer(TestPeer.freePort(), seed)) {
            FleetCapabilityTools toolsA = a.getBean(FleetCapabilityTools.class);
            FleetCapabilityTools toolsB = b.getBean(FleetCapabilityTools.class);
            assertThat(toolsA.toolCallbacks().getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                    .containsExactlyInAnyOrderElementsOf(FleetCapabilityTools.CONTENT_TOOLS);

            StringBuilder text = new StringBuilder();
            while (text.length() < 3 * 65536 + 100) {
                text.append("the quick brown fox jumps over the lazy dog ").append(text.length()).append('\n');
            }
            Path file = dir.resolve("notes.txt");
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
            String published = toolsA.publishContent(file.toString(), "notes.txt");
            assertThat(published).contains("\"name\":\"notes.txt\"").contains("\"pieces\":4");
            String root = field(published, "root");

            await(() -> toolsB.describeContent(root).contains("\"completeHolders\":1"), Duration.ofSeconds(30));
            assertThat(toolsB.fetchContent(root)).contains("\"complete\":true").contains("\"piecesVerified\":4");
            assertThat(toolsB.readContent(root, 60))
                    .contains("\"text\":\"the quick brown fox jumps over the lazy dog 44").contains("\"truncated\":true");
            assertThat(toolsB.listContent()).contains(root).contains("notes.txt");
            await(() -> toolsA.describeContent(root).contains("\"completeHolders\":2"), Duration.ofSeconds(30));
            assertThat(toolsA.readContent("bnotaroot", 10)).contains("error");
        }
    }
}
