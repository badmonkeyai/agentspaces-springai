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
import ai.badmonkey.agentspaces.connect.DataQueryClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Tools a model uses to reason about the fleet itself: find agents by meaning
 * (and learn which fleet tool invokes each), find data assets by meaning, and
 * read a data asset through the connector SDK, where an identical question
 * another agent already asked is answered from the space without touching the
 * source. Each tool is enabled separately; every result is capped in size
 * before it enters the model's context.
 */
public class FleetDiscoveryTools {

    /** Ranks the fleet's advertisements against a question: (question, limit) to matches. */
    public interface Search extends BiFunction<String, Integer, List<SemanticDiscovery.Match>> {
    }

    /** Reads a data asset: (asset, parameters) to the fetched result, if any arrived. */
    public interface Fetch extends BiFunction<String, Map<String, String>, Optional<DataQueryClient.Fetched>> {
    }

    private final Search search;
    private final Fetch fetch;
    private final Supplier<List<RemoteActionToolCallback>> fleetTools;
    private final JsonMapper json;
    private final int maxResultChars;
    private final java.util.Set<String> enabled;

    /**
     * Creates the tools.
     *
     * @param search         semantic search over the fleet's advertisements, or null when
     *                       neither discovery tool is enabled
     * @param fetch          the data fetch, or null when the data tool is disabled
     * @param fleetTools     the current F1 tools, to name the tool that invokes each agent
     * @param json           the JSON mapper results are written with
     * @param maxResultChars the cap on any result's length
     * @param enabled        the tool names {@link #toolCallbacks()} exposes
     */
    public FleetDiscoveryTools(Search search, Fetch fetch,
                               Supplier<List<RemoteActionToolCallback>> fleetTools,
                               JsonMapper json, int maxResultChars, java.util.Set<String> enabled) {
        this.search = search;
        this.fetch = fetch;
        this.fleetTools = Objects.requireNonNull(fleetTools, "fleetTools");
        this.json = Objects.requireNonNull(json, "json");
        this.maxResultChars = maxResultChars;
        this.enabled = java.util.Set.copyOf(enabled);
    }

    /**
     * The enabled tools, to attach to a ChatClient. Like {@code FleetTools},
     * this bean is not a {@code ToolCallbackProvider} itself, so Spring AI's MCP
     * server never publishes it implicitly.
     *
     * @return the enabled tools
     */
    public org.springframework.ai.tool.ToolCallbackProvider toolCallbacks() {
        return new FleetDiscoveryToolCallbackProvider(this, enabled);
    }

    /**
     * Finds fleet agents whose advertised skills match a question.
     *
     * @param question what the caller needs done, in plain language
     * @param limit    the most agents to return
     * @return the matching agents as JSON
     */
    @Tool(name = "find_fleet_agents", description = "Finds agents in the peer-to-peer fleet whose"
            + " advertised skills match a question. Returns each agent's name, description, goals,"
            + " input and output types, the name of the tool that invokes it (if exposed), and a"
            + " match score between 0 and 1.")
    public String findFleetAgents(
            @ToolParam(description = "What you need done, in plain language") String question,
            @ToolParam(description = "The most agents to return, 1 to 20", required = false) Integer limit) {
        Map<String, List<String>> toolsByAgent = new LinkedHashMap<>();
        for (RemoteActionToolCallback tool : fleetTools.get()) {
            toolsByAgent.computeIfAbsent(tool.action().agent().encoded(), k -> new ArrayList<>())
                    .add(tool.getToolDefinition().name());
        }
        List<Map<String, Object>> agents = new ArrayList<>();
        for (SemanticDiscovery.Match match : search(question, limit)) {
            if (match.advertisement() instanceof AgentCard card) {
                Map<String, Object> agent = new LinkedHashMap<>();
                agent.put("agent", card.agent().localName());
                agent.put("description", card.description());
                agent.put("goals", card.goals());
                agent.put("consumes", simpleNames(card.consumes()));
                agent.put("produces", simpleNames(card.produces()));
                agent.put("tools", toolsByAgent.getOrDefault(card.agent().encoded(), List.of()));
                agent.put("score", Math.round(match.score() * 1000) / 1000.0);
                agents.add(agent);
            }
        }
        return render(agents);
    }

    /**
     * Finds data assets whose descriptions match a question.
     *
     * @param question what data the caller needs, in plain language
     * @param limit    the most assets to return
     * @return the matching assets as JSON
     */
    @Tool(name = "list_fleet_assets", description = "Finds data assets that peers in the fleet"
            + " serve, by meaning. Returns each asset's name (use it with fetch_fleet_data), its"
            + " description, source URI, row shape, freshness window, and a match score.")
    public String listFleetAssets(
            @ToolParam(description = "What data you need, in plain language") String question,
            @ToolParam(description = "The most assets to return, 1 to 20", required = false) Integer limit) {
        List<Map<String, Object>> assets = new ArrayList<>();
        for (SemanticDiscovery.Match match : search(question, limit)) {
            if (match.advertisement() instanceof AssetCard card) {
                Map<String, Object> asset = new LinkedHashMap<>();
                asset.put("asset", card.asset());
                asset.put("description", card.description());
                asset.put("uri", card.uri());
                asset.put("shape", card.shape());
                asset.put("freshness", card.freshness());
                asset.put("score", Math.round(match.score() * 1000) / 1000.0);
                assets.add(asset);
            }
        }
        return render(assets);
    }

    /**
     * Reads a data asset through the fleet.
     *
     * @param asset      the asset's name, from list_fleet_assets
     * @param parameters the query parameters
     * @return the rows as JSON, or an explanation
     */
    @Tool(name = "fetch_fleet_data", description = "Reads rows from a data asset a peer in the"
            + " fleet serves. An identical question asked recently is answered from the fleet's"
            + " shared results without touching the source. Returns the rows, whether they came"
            + " from the shared results, and which peer served them.")
    public String fetchFleetData(
            @ToolParam(description = "The asset's name, as list_fleet_assets returns it") String asset,
            @ToolParam(description = "Query parameters as name-value pairs", required = false)
            Map<String, String> parameters) {
        if (fetch == null) {
            return render(Map.of("error", "fetch_fleet_data is not enabled on this node"));
        }
        Optional<DataQueryClient.Fetched> fetched = fetch.apply(asset,
                parameters == null ? Map.of() : Map.copyOf(parameters));
        if (fetched.isEmpty()) {
            return render(Map.of("error", "no peer answered for asset '" + asset + "' in time"));
        }
        DataQueryClient.Fetched result = fetched.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asset", result.result().asset());
        out.put("fromCache", result.fromCache());
        out.put("servedBy", result.result().servedBy());
        if (result.result().error() != null) {
            out.put("error", result.result().error());
        }
        out.put("rows", result.result().rows());
        return render(out);
    }

    private List<SemanticDiscovery.Match> search(String question, Integer limit) {
        if (search == null) {
            return List.of();
        }
        int bounded = limit == null ? 5 : Math.max(1, Math.min(20, limit));
        return search.apply(question, bounded);
    }

    private String render(Object value) {
        String text = json.writeValueAsString(value);
        if (text.length() <= maxResultChars) {
            return text;
        }
        return text.substring(0, maxResultChars - 60) + " ... [truncated at " + maxResultChars
                + " characters]";
    }

    private static List<String> simpleNames(List<String> schemas) {
        List<String> names = new ArrayList<>();
        for (String schema : schemas) {
            String type = schema.contains("#") ? schema.substring(0, schema.indexOf('#')) : schema;
            int cut = Math.max(type.lastIndexOf('.'), type.lastIndexOf('$'));
            names.add(cut >= 0 ? type.substring(cut + 1) : type);
        }
        return names;
    }
}
