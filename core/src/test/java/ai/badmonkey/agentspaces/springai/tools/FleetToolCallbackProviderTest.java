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

import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.OnTimeout;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class FleetToolCallbackProviderTest {

    private final List<FleetAction> fleet = new CopyOnWriteArrayList<>();
    private final List<FleetToolsChangedEvent> events = new ArrayList<>();
    private final MutableClock clock = new MutableClock();

    private FleetToolCallbackProvider provider(FleetToolFilter filter) {
        return new FleetToolCallbackProvider(() -> List.copyOf(fleet), filter,
                ToolNamingStrategy.prefixed("fleet_"), new JsonMapper(), Duration.ofSeconds(5),
                OnTimeout.RESULT, ObservationRegistry.NOOP, Duration.ofSeconds(1), events::add, clock);
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void theToolSetFollowsTheFleetAndReportsChanges() {
        FleetToolCallbackProvider provider = provider(action -> true);
        assertThat(provider.getToolCallbacks()).isEmpty();
        assertThat(events).isEmpty();

        fleet.add(FakeAction.summarizer());
        assertThat(names(provider.current())).as("cached: the snapshot is fresh").isEmpty();
        clock.advance(Duration.ofSeconds(2));
        assertThat(names(provider.current())).containsExactly("fleet_summarizer_Brief");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).added()).containsExactly("fleet_summarizer_Brief");
        assertThat(events.get(0).removed()).isEmpty();

        provider.refresh();
        assertThat(events).as("no change, no event").hasSize(1);

        fleet.clear();
        provider.refresh();
        assertThat(provider.getToolCallbacks()).isEmpty();
        assertThat(events).hasSize(2);
        assertThat(events.get(1).removed()).containsExactly("fleet_summarizer_Brief");
    }

    @Test
    void collidingNamesAreMadeUniqueAndTheFilterApplies() {
        fleet.add(FakeAction.summarizer());
        fleet.add(FakeAction.summarizer());
        fleet.add(new FakeAction("secret", false, in -> Optional.empty()));
        FleetToolCallbackProvider provider = provider(
                FleetToolFilter.of(List.of(), List.of("secret"), false));
        assertThat(names(provider.refresh()))
                .containsExactly("fleet_summarizer_Brief", "fleet_summarizer_Brief_2");
    }

    /** A clock tests move by hand. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
