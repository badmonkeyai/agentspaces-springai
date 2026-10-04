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
package ai.badmonkey.agentspaces.springai.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Every {@code agentspaces.springai.*} property, as immutable records bound by
 * constructor. Each nested record is one feature; each validates itself in its
 * compact constructor, so a misconfiguration fails at startup with the name of
 * the property.
 *
 * @param group          the group this project works in; empty for the first
 *                       group under {@code agentspaces.groups}
 * @param tools          F1, fleet tools
 * @param discoveryTools F2, discovery and data tools
 * @param takeLease      F3, the take-lease advisor
 * @param memory         F3, space-backed chat memory
 * @param modelClient    F4, the calling side of model access
 * @param modelServer    F4, the serving side of model access
 * @param embedder       F5, the embedder semantic discovery uses
 * @param usage          F6, fleet-wide usage
 * @param mcp            F7, MCP export
 * @param vectorStore    F8, the application's {@code VectorStore} as a fleet data asset
 */
@ConfigurationProperties("agentspaces.springai")
public record AgentSpacesSpringAiProperties(
        @DefaultValue("") String group,
        @DefaultValue Tools tools,
        @DefaultValue DiscoveryTools discoveryTools,
        @DefaultValue TakeLease takeLease,
        @DefaultValue Memory memory,
        @DefaultValue ModelClient modelClient,
        @DefaultValue ModelServer modelServer,
        @DefaultValue Embedder embedder,
        @DefaultValue Usage usage,
        @DefaultValue Mcp mcp,
        @DefaultValue VectorStore vectorStore) {

    /** The defaults, for code that builds components without a Spring context. */
    public static AgentSpacesSpringAiProperties defaults() {
        return new AgentSpacesSpringAiProperties("", Tools.defaults(), DiscoveryTools.defaults(),
                TakeLease.defaults(), Memory.defaults(), ModelClient.defaults(),
                ModelServer.defaults(), Embedder.defaults(), Usage.defaults(), Mcp.defaults(),
                VectorStore.defaults());
    }

    /** What a fleet tool call does when no result arrives in time. */
    public enum OnTimeout {
        /** Return a tool result that tells the model the fleet produced nothing in time. */
        RESULT,
        /** Raise a {@code ToolExecutionException}. */
        THROW
    }

    /**
     * F1: the fleet's AgentCards as tools.
     *
     * @param enabled         register the provider
     * @param prefix          tool-name prefix
     * @param includeAgents   agent local names or AgentIds to include; empty includes all
     * @param excludeAgents   agent local names or AgentIds to exclude
     * @param requireAttested only cards that carry the agent's own key become tools
     * @param timeout         how long one fleet tool call waits
     * @param onTimeout       what a timeout returns
     * @param refreshInterval how often the tool set is diffed for change events
     * @param cacheTtl        how long a tool-set snapshot serves requests
     */
    public record Tools(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("fleet_") String prefix,
            @DefaultValue List<String> includeAgents,
            @DefaultValue List<String> excludeAgents,
            @DefaultValue("false") boolean requireAttested,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("result") OnTimeout onTimeout,
            @DefaultValue("5s") Duration refreshInterval,
            @DefaultValue("1s") Duration cacheTtl) {

        /** Validates the values. */
        public Tools {
            includeAgents = List.copyOf(includeAgents);
            excludeAgents = List.copyOf(excludeAgents);
            positive(timeout, "agentspaces.springai.tools.timeout");
            positive(refreshInterval, "agentspaces.springai.tools.refresh-interval");
            if (cacheTtl.isNegative()) {
                throw new IllegalArgumentException("agentspaces.springai.tools.cache-ttl must not be negative");
            }
            if (!prefix.matches("[A-Za-z0-9_-]*")) {
                throw new IllegalArgumentException("agentspaces.springai.tools.prefix may use only"
                        + " letters, digits, '_' and '-': " + prefix);
            }
        }

        static Tools defaults() {
            return new Tools(true, "fleet_", List.of(), List.of(), false, Duration.ofSeconds(30),
                    OnTimeout.RESULT, Duration.ofSeconds(5), Duration.ofSeconds(1));
        }
    }

    /**
     * F2: tools that reason about the fleet itself. Each is enabled separately.
     *
     * @param agents         register {@code find_fleet_agents}
     * @param assets         register {@code list_fleet_assets}
     * @param data           register {@code fetch_fleet_data}
     * @param dataSpace      the space data queries travel through
     * @param timeout        how long a remote discovery query or data fetch waits
     * @param maxResultChars cap on the text any of these tools returns to the model
     */
    public record DiscoveryTools(
            @DefaultValue("false") boolean agents,
            @DefaultValue("false") boolean assets,
            @DefaultValue("false") boolean data,
            @DefaultValue("data") String dataSpace,
            @DefaultValue("10s") Duration timeout,
            @DefaultValue("20000") int maxResultChars) {

        /** Validates the values. */
        public DiscoveryTools {
            positive(timeout, "agentspaces.springai.discovery-tools.timeout");
            if (maxResultChars < 256) {
                throw new IllegalArgumentException(
                        "agentspaces.springai.discovery-tools.max-result-chars must be at least 256");
            }
        }

        static DiscoveryTools defaults() {
            return new DiscoveryTools(false, false, false, "data", Duration.ofSeconds(10), 20_000);
        }
    }

    /**
     * F3: renew the in-flight take lease on every model round-trip.
     *
     * @param enabled              add the advisor to every auto-configured ChatClient
     * @param streamRenewInterval  renewal interval while a streamed response runs
     */
    public record TakeLease(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") Duration streamRenewInterval) {

        /** Validates the values. */
        public TakeLease {
            positive(streamRenewInterval, "agentspaces.springai.take-lease.stream-renew-interval");
        }

        static TakeLease defaults() {
            return new TakeLease(true, Duration.ofSeconds(30));
        }
    }

    /** How a conversation is keyed when the caller names none. */
    public enum ConversationIdMode {
        /** The in-flight task's entry ID: a reassigned task resumes its conversation. */
        TAKE,
        /** The bound agent's ID: one long-running memory per agent. */
        AGENT,
        /** The caller always passes {@code ChatMemory.CONVERSATION_ID}. */
        EXPLICIT
    }

    /**
     * F3: chat memory in a replicated space.
     *
     * @param enabled        register the repository and the memory advisor
     * @param space          the space that holds conversation snapshots
     * @param ttl            snapshot lease
     * @param conversationId the default conversation key
     * @param maxMessages    the message window
     */
    public record Memory(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("conversations") String space,
            @DefaultValue("24h") Duration ttl,
            @DefaultValue("take") ConversationIdMode conversationId,
            @DefaultValue("20") int maxMessages) {

        /** Validates the values. */
        public Memory {
            positive(ttl, "agentspaces.springai.memory.ttl");
            if (maxMessages < 1) {
                throw new IllegalArgumentException("agentspaces.springai.memory.max-messages must be positive");
            }
        }

        static Memory defaults() {
            return new Memory(false, "conversations", Duration.ofHours(24), ConversationIdMode.TAKE, 20);
        }
    }

    /** What a streaming caller does when a server dies mid-stream. */
    public enum RestartMode {
        /** End the stream with {@code FleetStreamRestartedException}, carrying the partial text. */
        FAIL,
        /** Emit a restart marker, then the new attempt from its beginning. */
        RESTART
    }

    /**
     * F4, calling side.
     *
     * @param enabled register the {@code fleetChatModel} bean
     * @param space   where requests are written
     * @param model   the model name requests ask for when the prompt names none
     * @param timeout how long one round-trip waits
     * @param stream  streaming behavior
     */
    public record ModelClient(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("model-requests") String space,
            @DefaultValue("") String model,
            @DefaultValue("120s") Duration timeout,
            @DefaultValue Stream stream) {

        /** Validates the values. */
        public ModelClient {
            positive(timeout, "agentspaces.springai.model-client.timeout");
        }

        static ModelClient defaults() {
            return new ModelClient(false, "model-requests", "", Duration.ofSeconds(120), Stream.defaults());
        }

        /**
         * Streaming behavior.
         *
         * @param onRestart what happens when a server dies mid-stream
         */
        public record Stream(@DefaultValue("fail") RestartMode onRestart) {
            static Stream defaults() {
                return new Stream(RestartMode.FAIL);
            }
        }
    }

    /** How model servers divide requests between them. */
    public enum Routing {
        /** LEASE_RACE: a server takes only requests for models it serves. */
        MODEL,
        /** AUCTION: servers bid by price and load; the space runs the AUCTION strategy. */
        PRICE
    }

    /**
     * F4, serving side.
     *
     * @param enabled         serve requests with this peer's ChatModel beans
     * @param space           where requests arrive
     * @param models          the model names this peer serves; the first is the default
     * @param routing         by model name or by price
     * @param prices          per-model price per thousand tokens, for price routing
     * @param lease           take lease, renewed while a call runs
     * @param chunkInterval   stream batching: the longest a chunk waits
     * @param chunkMaxDeltas  stream batching: the most deltas one chunk carries
     * @param chunkLease      lease of stream chunk entries
     * @param responseLease   lease of response entries
     * @param concurrency     how many requests this server works at once
     * @param modelBeans      model name to ChatModel bean name, when several providers are present
     */
    public record ModelServer(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("model-requests") String space,
            @DefaultValue List<String> models,
            @DefaultValue("model") Routing routing,
            @DefaultValue Map<String, Double> prices,
            @DefaultValue("2m") Duration lease,
            @DefaultValue("100ms") Duration chunkInterval,
            @DefaultValue("32") int chunkMaxDeltas,
            @DefaultValue("2m") Duration chunkLease,
            @DefaultValue("10m") Duration responseLease,
            @DefaultValue("4") int concurrency,
            @DefaultValue Map<String, String> modelBeans) {

        /** Validates the values. */
        public ModelServer {
            models = List.copyOf(models);
            prices = Map.copyOf(prices);
            modelBeans = Map.copyOf(modelBeans);
            positive(responseLease, "agentspaces.springai.model-server.response-lease");
            if (concurrency < 1) {
                throw new IllegalArgumentException("agentspaces.springai.model-server.concurrency must be positive");
            }
            positive(lease, "agentspaces.springai.model-server.lease");
            positive(chunkInterval, "agentspaces.springai.model-server.chunk-interval");
            positive(chunkLease, "agentspaces.springai.model-server.chunk-lease");
            if (chunkMaxDeltas < 1) {
                throw new IllegalArgumentException(
                        "agentspaces.springai.model-server.chunk-max-deltas must be positive");
            }
        }

        static ModelServer defaults() {
            return new ModelServer(false, "model-requests", List.of(), Routing.MODEL, Map.of(),
                    Duration.ofMinutes(2), Duration.ofMillis(100), 32, Duration.ofMinutes(2),
                    Duration.ofMinutes(10), 4, Map.of());
        }
    }

    /** Which embedder semantic discovery uses. */
    public enum EmbedderType {
        /** The deterministic hashing embedder: no model, no dependency. */
        HASHING,
        /** The application's Spring AI EmbeddingModel. */
        SPRING_AI
    }

    /**
     * F5: the embedder behind semantic discovery.
     *
     * @param type         hashing or spring-ai; every peer in a group must agree
     * @param requireMatch send remote queries only to peers advertising the same embedder
     * @param cacheSize    how many advertisement embeddings to cache
     * @param model        the embedding model's name, advertised as the embedder's identity;
     *                     empty for the EmbeddingModel's class name
     */
    public record Embedder(
            @DefaultValue("hashing") EmbedderType type,
            @DefaultValue("true") boolean requireMatch,
            @DefaultValue("4096") int cacheSize,
            @DefaultValue("") String model) {

        /** Validates the values. */
        public Embedder {
            if (cacheSize < 0) {
                throw new IllegalArgumentException("agentspaces.springai.embedder.cache-size must not be negative");
            }
        }

        static Embedder defaults() {
            return new Embedder(EmbedderType.HASHING, true, 4096, "");
        }
    }

    /**
     * F6: fleet-wide token usage.
     *
     * @param enabled      register the observation handler
     * @param metricsSpace also write a ModelUsage entry per call to this space; empty for none
     * @param entryLease      lease of ModelUsage entries
     * @param publishInterval how often this peer joins the fleet-wide token sums
     */
    public record Usage(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String metricsSpace,
            @DefaultValue("24h") Duration entryLease,
            @DefaultValue("30s") Duration publishInterval) {

        /** Validates the values. */
        public Usage {
            positive(entryLease, "agentspaces.springai.usage.entry-lease");
            positive(publishInterval, "agentspaces.springai.usage.publish-interval");
        }

        static Usage defaults() {
            return new Usage(false, "", Duration.ofHours(24), Duration.ofSeconds(30));
        }
    }

    /**
     * F7: MCP export.
     *
     * @param enabled       keep the MCP server's tools in step with the fleet
     * @param includeAgents  fleet agents the MCP server may expose; empty exposes none
     * @param discoveryTools also expose the enabled F2 discovery tools
     */
    public record Mcp(
            @DefaultValue("false") boolean enabled,
            @DefaultValue List<String> includeAgents,
            @DefaultValue("false") boolean discoveryTools) {

        /** Copies the list. */
        public Mcp {
            includeAgents = List.copyOf(includeAgents);
        }

        static Mcp defaults() {
            return new Mcp(false, List.of(), false);
        }
    }

    /**
     * F8: the application's {@code VectorStore} served to the fleet as a data
     * asset, through the connector SDK.
     *
     * @param enabled     serve the store
     * @param space       the data space queries and results flow through
     * @param asset       the asset's name, as agents fetch it
     * @param description what the store holds, for discovery by meaning
     * @param freshness   how long an answer stays valid in the space
     */
    public record VectorStore(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("data") String space,
            @DefaultValue("knowledge-base") String asset,
            @DefaultValue("The application's knowledge base") String description,
            @DefaultValue("5m") Duration freshness) {

        /** Validates. */
        public VectorStore {
            if (space == null || space.isBlank()) {
                throw new IllegalArgumentException("agentspaces.springai.vector-store.space must not be blank");
            }
            if (asset == null || asset.isBlank()) {
                throw new IllegalArgumentException("agentspaces.springai.vector-store.asset must not be blank");
            }
            positive(freshness, "agentspaces.springai.vector-store.freshness");
        }

        static VectorStore defaults() {
            return new VectorStore(false, "data", "knowledge-base", "The application's knowledge base",
                    Duration.ofMinutes(5));
        }
    }

    private static void positive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration");
        }
    }
}
