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
package ai.badmonkey.agentspaces.springai.usage;

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ObservedChatModel;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F6 over TCP: two Spring workers make observed model calls inside
 * {@code @SpaceTake} methods; each call lands in the metrics space attributed
 * to its worker's agent, and push-sum gives every peer the fleet's total.
 */
class FleetUsageFlowTest {

    private static final String FOUNDING = "springai-usage-flow-v1";

    public record Job(String name) {
    }

    public record Done(String name) {
    }

    @AgentSpec(name = "worker", description = "Works jobs with a model")
    public static class Worker {
        private final ChatModel model;

        Worker(ChatModel model) {
            this.model = model;
        }

        @SpaceTake(space = "jobs", pollTimeout = "PT0.2S")
        public Done work(Job job) {
            model.call(new Prompt(job.name()));
            return new Done(job.name());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class WorkerApp {
        @Bean
        ChatModel observed(ObservationRegistry registry) {
            return new ObservedChatModel(registry, "gpt-test", 100, 20);
        }

        @Bean
        Worker worker(ChatModel model) {
            return new Worker(model);
        }
    }

    private static ConfigurableApplicationContext worker(int seed) {
        return new SpringApplicationBuilder(WorkerApp.class)
                .properties(FleetApps.nodeProperties(FOUNDING, seed, "jobs", "metrics"))
                .properties(Map.of("agentspaces.springai.usage.enabled", "true",
                        "agentspaces.springai.usage.metrics-space", "metrics",
                        "agentspaces.springai.usage.publish-interval", "2s"))
                .run();
    }

    @Test
    @Timeout(120)
    void usageLandsAttributedAndTheFleetTotalIsKnownEverywhere() throws Exception {
        int seed = TestPeer.freePort();
        try (TestPeer desk = TestPeer.start(FOUNDING, "test-fleet", seed, 0, "jobs", "metrics");
             ConfigurableApplicationContext a = worker(seed);
             ConfigurableApplicationContext b = worker(seed)) {
            Thread.sleep(1500);
            for (int i = 0; i < 4; i++) {
                desk.spaces.get("jobs").write(new Job("job-" + i), Lease.of(Duration.ofMinutes(5)));
            }
            await(() -> desk.spaces.get("jobs").readAll(Template.of(Done.class), 10).size() == 4,
                    Duration.ofSeconds(30));
            await(() -> desk.spaces.get("metrics").readAll(Template.of(ModelUsage.class), 10).size() == 4,
                    Duration.ofSeconds(30));
            List<ModelUsage> usage = desk.spaces.get("metrics").readAll(Template.of(ModelUsage.class), 10);
            assertThat(usage).allSatisfy(u -> {
                assertThat(u.totalTokens()).isEqualTo(120);
                assertThat(u.agent()).endsWith("/worker");
            });

            FleetUsage usageA = a.getBean(FleetUsage.class);
            long localA = usageA.localByModel().getOrDefault("gpt-test", 0L);
            long localB = b.getBean(FleetUsage.class).localByModel().getOrDefault("gpt-test", 0L);
            assertThat(localA + localB).isEqualTo(480);
            await(() -> {
                OptionalDouble total = usageA.fleetTotal("gpt-test");
                return total.isPresent() && Math.abs(total.getAsDouble() - 480) < 1;
            }, Duration.ofSeconds(40));
            // The push-sum estimate keeps converging; the panel rounds it, so wait
            // for the rounded figure rather than read it once (480.6 shows as 481).
            await(() -> a.getBean(UsagePanel.class).data().toString().contains("gpt-test=480"),
                    Duration.ofSeconds(20));
            assertThat(a.getBean(UsagePanel.class).data().toString()).contains("fleetByModel");
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
