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

import ai.badmonkey.agentspaces.api.ad.AgentCard;
import ai.badmonkey.agentspaces.api.ad.AssetCard;
import ai.badmonkey.agentspaces.capabilities.semantic.SemanticDiscovery;
import ai.badmonkey.agentspaces.common.id.GroupId;
import ai.badmonkey.agentspaces.connect.DataQueryClient;
import ai.badmonkey.agentspaces.connect.DataQueryEntries;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FleetDiscoveryToolsTest {

    private static final PeerIdentity PEER = PeerIdentity.generate();
    private static final GroupId GROUP = GroupId.of("zDisc");

    private final AgentCard card = new AgentCard("aspace://g/agent/summarizer", PEER.peerId(), GROUP,
            Instant.now(), Duration.ofMinutes(15), PEER.agent("summarizer"), "Summarizes briefs",
            List.of("summarize"), List.of("com.example.Fleet$Brief#v1"),
            List.of("com.example.Fleet$Summary#v1"), Map.of());
    private final AssetCard asset = new AssetCard("aspace://g/asset/orders", PEER.peerId(), GROUP,
            Instant.now(), Duration.ofMinutes(15), "orders", "postgres://ops/orders",
            "Customer order history", "com.example.OrderRow#v1", "PT5M", Map.of(), Map.of());

    private FleetDiscoveryTools tools(FleetDiscoveryTools.Fetch fetch, int maxChars) {
        FakeAction action = new FakeAction("summarizer", false, in -> Optional.empty()) {
        };
        RemoteActionToolCallback fleetTool = new RemoteActionToolCallback(
                new DelegatingAction(action, PEER), "fleet_summarizer_Brief", new JsonMapper(),
                Duration.ofSeconds(1), ai.badmonkey.agentspaces.springai.autoconfigure
                .AgentSpacesSpringAiProperties.OnTimeout.RESULT,
                io.micrometer.observation.ObservationRegistry.NOOP);
        return new FleetDiscoveryTools(
                (question, limit) -> List.of(new SemanticDiscovery.Match(card, 0.8123),
                        new SemanticDiscovery.Match(asset, 0.51)),
                fetch, () -> List.of(fleetTool), new JsonMapper(), maxChars,
                Set.of("find_fleet_agents", "list_fleet_assets", "fetch_fleet_data"));
    }

    @Test
    void findingAgentsNamesTheToolThatInvokesEach() {
        String result = tools(null, 20_000).findFleetAgents("summarize a brief", 5);
        assertThat(result).contains("\"agent\":\"summarizer\"", "\"consumes\":[\"Brief\"]",
                "\"produces\":[\"Summary\"]", "\"tools\":[\"fleet_summarizer_Brief\"]", "\"score\":0.812")
                .doesNotContain("orders");
    }

    @Test
    void listingAssetsReturnsOnlyAssets() {
        String result = tools(null, 20_000).listFleetAssets("orders", null);
        assertThat(result).contains("\"asset\":\"orders\"", "\"freshness\":\"PT5M\"")
                .doesNotContain("summarizer");
    }

    @Test
    void fetchingDataReportsRowsAndWhetherTheSpaceAnswered() {
        DataQueryEntries.DataResult rows = new DataQueryEntries.DataResult("h", "orders",
                List.of(Map.of("id", "1", "region", "eu")), null, "peer:z1");
        String result = tools((a, p) -> Optional.of(new DataQueryClient.Fetched(rows, true)), 20_000)
                .fetchFleetData("orders", Map.of("region", "eu"));
        assertThat(result).contains("\"fromCache\":true", "\"servedBy\":\"peer:z1\"", "\"region\":\"eu\"");
        assertThat(tools((a, p) -> Optional.empty(), 20_000).fetchFleetData("orders", null))
                .contains("no peer answered");
        assertThat(tools(null, 20_000).fetchFleetData("orders", null)).contains("not enabled");
    }

    @Test
    void resultsAreCappedBeforeTheyReachTheModel() {
        DataQueryEntries.DataResult big = new DataQueryEntries.DataResult("h", "orders",
                List.of(Map.of("blob", "x".repeat(5_000))), null, "peer:z1");
        String result = tools((a, p) -> Optional.of(new DataQueryClient.Fetched(big, false)), 300)
                .fetchFleetData("orders", Map.of());
        assertThat(result).hasSizeLessThanOrEqualTo(300).endsWith("[truncated at 300 characters]");
    }

    @Test
    void onlyTheEnabledToolsAreExposed() {
        ToolCallback[] callbacks = new FleetDiscoveryToolCallbackProvider(tools(null, 20_000),
                Set.of("find_fleet_agents")).getToolCallbacks();
        assertThat(callbacks).extracting(c -> c.getToolDefinition().name())
                .containsExactly("find_fleet_agents");
        assertThat(callbacks[0].getToolDefinition().inputSchema()).contains("question");
    }

    /** Pins the fake action's agent to the card's peer so the tool maps to the card. */
    private record DelegatingAction(FakeAction delegate, PeerIdentity peer) implements FleetAction {
        @Override public String name() { return delegate.name(); }
        @Override public String description() { return delegate.description(); }
        @Override public List<String> goals() { return delegate.goals(); }
        @Override public ai.badmonkey.agentspaces.common.id.AgentId agent() { return peer.agent("summarizer"); }
        @Override public ai.badmonkey.agentspaces.common.id.PeerId issuer() { return peer.peerId(); }
        @Override public boolean attested() { return false; }
        @Override public Class<?> inputType() { return delegate.inputType(); }
        @Override public Class<?> outputType() { return delegate.outputType(); }
        @Override public Optional<Object> invoke(Object input, Duration timeout) { return delegate.invoke(input, timeout); }
    }
}
