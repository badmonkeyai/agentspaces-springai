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

import ai.badmonkey.agentspaces.common.id.AgentId;
import ai.badmonkey.agentspaces.common.id.PeerId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.OnTimeout;
import ai.badmonkey.agentspaces.springai.tools.FleetAction;
import ai.badmonkey.agentspaces.springai.tools.FleetToolCallbackProvider;
import ai.badmonkey.agentspaces.springai.tools.FleetToolFilter;
import ai.badmonkey.agentspaces.springai.tools.FleetTools;
import ai.badmonkey.agentspaces.springai.tools.FleetToolsChangedEvent;
import ai.badmonkey.agentspaces.springai.tools.ToolNamingStrategy;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** {@link FleetMcpToolSync} in isolation: fleet changes in, MCP server calls out. */
class FleetMcpToolSyncTest {

    private static final PeerIdentity PEER = PeerIdentity.generate();

    record Brief(String topic) {
    }

    private final List<FleetAction> fleet = new CopyOnWriteArrayList<>();
    private final FleetTools tools = new FleetTools(new FleetToolCallbackProvider(() -> List.copyOf(fleet),
            FleetToolFilter.of(List.of(), List.of(), false), ToolNamingStrategy.prefixed("fleet_"),
            new JsonMapper(), Duration.ofSeconds(5), OnTimeout.RESULT, ObservationRegistry.NOOP,
            Duration.ofMillis(1), event -> { }, Clock.systemUTC()));
    private final McpSyncServer server = mock(McpSyncServer.class);

    private static FleetAction action(String agent) {
        return new FleetAction() {
            @Override public String name() { return agent + "_Brief"; }
            @Override public String description() { return "handles briefs"; }
            @Override public List<String> goals() { return List.of(); }
            @Override public AgentId agent() { return PEER.agent(agent); }
            @Override public PeerId issuer() { return PEER.peerId(); }
            @Override public boolean attested() { return false; }
            @Override public Class<?> inputType() { return Brief.class; }
            @Override public Class<?> outputType() { return Brief.class; }
            @Override public Optional<Object> invoke(Object input, Duration timeout) { return Optional.of(input); }
        };
    }

    private FleetToolsChangedEvent changed() {
        return new FleetToolsChangedEvent(Set.of(), Set.of(), tools.refresh());
    }

    private List<String> added(int times) {
        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> specs =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server, times(times)).addTool(specs.capture());
        return specs.getAllValues().stream().map(spec -> spec.tool().name()).toList();
    }

    @Test
    void onStartItPublishesOnlyTheIncludedAgentsAndAnnouncesTheList() {
        fleet.add(action("summarizer"));
        fleet.add(action("secret"));
        FleetMcpToolSync sync = new FleetMcpToolSync(server, tools, List.of("summarizer"), List.of());

        sync.start();

        assertThat(added(1)).containsExactly("fleet_summarizer_Brief");
        verify(server, times(1)).notifyToolsListChanged();
        assertThat(sync.published()).containsExactly("fleet_summarizer_Brief");
        assertThat(sync.isRunning()).isTrue();
    }

    @Test
    void withNoIncludedAgentsNothingFromTheFleetIsExposed() {
        fleet.add(action("summarizer"));
        FleetMcpToolSync sync = new FleetMcpToolSync(server, tools, List.of(), List.of());

        sync.start();
        sync.onFleetToolsChanged(changed());

        verifyNoInteractions(server);
        assertThat(sync.published()).isEmpty();
    }

    @Test
    void anArrivingCardAddsItsToolAndALapsedOneRemovesIt() {
        fleet.add(action("summarizer"));
        FleetMcpToolSync sync = new FleetMcpToolSync(server, tools, List.of("summarizer", "translator"),
                List.of());
        sync.start();

        fleet.add(action("translator"));
        sync.onFleetToolsChanged(changed());
        assertThat(added(2)).containsExactly("fleet_summarizer_Brief", "fleet_translator_Brief");

        fleet.removeIf(a -> a.name().equals("summarizer_Brief"));
        sync.onFleetToolsChanged(changed());
        verify(server).removeTool("fleet_summarizer_Brief");
        verify(server, times(3)).notifyToolsListChanged();
        assertThat(sync.published()).containsExactly("fleet_translator_Brief");
    }

    @Test
    void anUnchangedFleetSendsNoNotification() {
        fleet.add(action("summarizer"));
        FleetMcpToolSync sync = new FleetMcpToolSync(server, tools, List.of("summarizer"), List.of());
        sync.start();

        sync.onFleetToolsChanged(changed());
        sync.onFleetToolsChanged(changed());

        verify(server, times(1)).addTool(org.mockito.ArgumentMatchers.any());
        verify(server, times(1)).notifyToolsListChanged();
        verify(server, never()).removeTool(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void extraToolsArePublishedAsTheyAre() {
        ToolCallback extra = FunctionToolCallback.builder("find_fleet_agents", (Brief in) -> "none")
                .description("finds agents").inputType(Brief.class).build();
        FleetMcpToolSync sync = new FleetMcpToolSync(server, tools, List.of(), List.of(extra));

        sync.start();

        assertThat(added(1)).containsExactly("find_fleet_agents");
    }

    @Test
    void afterStopEventsAreIgnored() {
        FleetMcpToolSync sync = new FleetMcpToolSync(server, tools, List.of("summarizer"), List.of());
        sync.start();
        sync.stop();

        fleet.add(action("summarizer"));
        sync.onFleetToolsChanged(changed());

        verifyNoInteractions(server);
        assertThat(sync.isRunning()).isFalse();
    }
}
