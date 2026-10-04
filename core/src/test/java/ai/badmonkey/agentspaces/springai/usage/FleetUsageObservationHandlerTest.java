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

import ai.badmonkey.agentspaces.agent.TakeContext;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.space.local.LocalSpace;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;
import ai.badmonkey.agentspaces.springai.support.FakeTake;
import ai.badmonkey.agentspaces.springai.support.ObservedChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class FleetUsageObservationHandlerTest {

    @Test
    void everyObservedModelCallIsRecordedAndAttributedToTheWorkersAgent() {
        List<ModelUsage> recorded = new CopyOnWriteArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new FleetUsageObservationHandler(recorded::add, "peer:z1"));
        ObservedChatModel model = new ObservedChatModel(registry, "gpt-test", 10, 5);

        model.call(new Prompt("unattributed"));
        FakeTake take = new FakeTake();
        TakeContext worker = take.context("researcher");
        TakeContext.bind(worker);
        try {
            model.call(new Prompt("attributed"));
        } finally {
            TakeContext.unbind();
        }

        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(0)).isEqualTo(new ModelUsage("gpt-test", "scripted", 10, 5, 15, "", "peer:z1"));
        assertThat(recorded.get(1).agent()).isEqualTo(worker.agent().encoded());
    }

    @Test
    void theDefaultSinkCountsPerModelAndAgentAndWritesEntries() {
        LocalSpace metrics = LocalSpace.builder("metrics", PeerIdentity.generate().agent("host")).build();
        try {
            FleetUsage usage = new FleetUsage(null, metrics, Duration.ofHours(1), Duration.ofSeconds(30),
                    Clock.systemUTC());
            usage.record(new ModelUsage("m1", "p", 1, 2, 3, "a/x", "peer:z1"));
            usage.record(new ModelUsage("m1", "p", 1, 1, 2, "", "peer:z1"));
            usage.record(new ModelUsage("m2", "p", 5, 5, 10, "a/x", "peer:z1"));
            assertThat(usage.localByModel()).containsEntry("m1", 5L).containsEntry("m2", 10L);
            assertThat(usage.localByAgent()).containsEntry("a/x", 13L).containsEntry("(unattributed)", 2L);
            assertThat(metrics.readAll(Template.of(ModelUsage.class), 10)).hasSize(3);
            assertThat(usage.fleetTotal("m1")).as("no aggregate, no fleet figure").isEmpty();
            assertThat(new UsagePanel(usage).data().toString()).contains("thisPeerByModel", "m1=5");
        } finally {
            metrics.close();
        }
    }
}
