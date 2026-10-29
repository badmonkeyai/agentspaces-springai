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

import ai.badmonkey.agentspaces.agent.capability.AggregateClient;
import ai.badmonkey.agentspaces.agent.capability.VoteClient;
import ai.badmonkey.agentspaces.agent.content.Manifest;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.content.Content;
import ai.badmonkey.agentspaces.content.Fetch;
import ai.badmonkey.agentspaces.content.PublishOptions;
import ai.badmonkey.agentspaces.springai.FleetContext;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tools a model uses to take part in the fleet's capabilities: open a vote,
 * cast a ballot, read a tally or a decision, contribute to a push-sum epoch,
 * and read its estimate. The model acts as this peer's agent through the typed
 * clients the group resolves ({@code VoteClient}, {@code AggregateClient}), so
 * a ballot is that agent's attested record and a contribution joins the
 * group's epoch. Each family (vote, aggregate) is enabled separately; a tool
 * whose capability the group does not provide answers with an error the model
 * can read, rather than failing the tool loop.
 */
public class FleetCapabilityTools {

    /** The vote family's tool names. */
    public static final List<String> VOTE_TOOLS = List.of("propose_vote", "cast_ballot", "read_tally",
            "read_decision");

    /** The aggregate family's tool names. */
    public static final List<String> AGGREGATE_TOOLS = List.of("contribute", "read_estimate");

    /** The content family's tool names (srad/large-networks-bulk-data.md §7.2). */
    public static final List<String> CONTENT_TOOLS = List.of("publish_content", "fetch_content", "read_content",
            "describe_content", "list_content");

    private final Supplier<VoteClient> vote;
    private final Supplier<AggregateClient> aggregate;
    private final Supplier<Content> content;
    private final Duration ballotLease;
    private final Duration settleTimeout;
    private final Duration fetchTimeout;
    private final JsonMapper json;
    private final Set<String> enabled;

    /**
     * Creates the tools over the fleet's group context, resolving each client
     * when a tool first needs it.
     *
     * @param fleet         the fleet context
     * @param vote          expose the vote tools
     * @param aggregate     expose the aggregate tools
     * @param ballotLease   the lease of proposals and ballots the tools write
     * @param settleTimeout how long {@code read_estimate} waits for an estimate to settle
     * @param json          the JSON mapper results are written with
     */
    public FleetCapabilityTools(FleetContext fleet, boolean vote, boolean aggregate, Duration ballotLease,
                                Duration settleTimeout, JsonMapper json) {
        this(fleet, vote, aggregate, false, ballotLease, settleTimeout, Duration.ofMinutes(10), json);
    }

    /**
     * Creates the tools over the fleet's group context with the content family too.
     *
     * @param fleet         the fleet context
     * @param vote          whether the vote tools are on
     * @param aggregate     whether the aggregate tools are on
     * @param content       whether the content swarm tools are on ({@code agentspaces.content.enabled} must build one)
     * @param ballotLease   the lease of proposals and ballots the tools write
     * @param settleTimeout how long {@code read_estimate} waits for an estimate to settle
     * @param fetchTimeout  how long {@code fetch_content} waits for an artifact
     * @param json          the JSON mapper results are written with
     */
    public FleetCapabilityTools(FleetContext fleet, boolean vote, boolean aggregate, boolean content,
                                Duration ballotLease, Duration settleTimeout, Duration fetchTimeout, JsonMapper json) {
        this(() -> fleet.group().capability(VoteClient.class),
                () -> fleet.group().capability(AggregateClient.class),
                () -> contentOf(fleet),
                ballotLease, settleTimeout, fetchTimeout, json, enabled(vote, aggregate, content));
    }

    /** The group's content swarm, when {@code agentspaces.content.enabled} built one. */
    public static Content contentOf(FleetContext fleet) {
        return fleet.group().provider(Content.TYPE)
                .filter(Content.class::isInstance)
                .map(Content.class::cast)
                .orElseThrow(() -> new IllegalStateException(
                        "the content swarm is not enabled in this group (agentspaces.content.enabled=true)"));
    }

    /**
     * Creates the tools over explicit clients.
     *
     * @param vote          resolves the vote client; may throw when the group provides no vote
     * @param aggregate     resolves the aggregate client; may throw when the group provides none
     * @param ballotLease   the lease of proposals and ballots the tools write
     * @param settleTimeout how long {@code read_estimate} waits for an estimate to settle
     * @param json          the JSON mapper results are written with
     * @param enabled       the tool names {@link #toolCallbacks()} exposes
     */
    public FleetCapabilityTools(Supplier<VoteClient> vote, Supplier<AggregateClient> aggregate,
                                Duration ballotLease, Duration settleTimeout, JsonMapper json,
                                Set<String> enabled) {
        this(vote, aggregate, () -> {
            throw new IllegalStateException("no content swarm was given to these tools");
        }, ballotLease, settleTimeout, Duration.ofMinutes(10), json, enabled);
    }

    /**
     * Creates the tools over explicit clients, the content swarm included.
     *
     * @param vote          resolves the vote client; may throw when the group provides no vote
     * @param aggregate     resolves the aggregate client; may throw when the group provides none
     * @param content       resolves the content swarm; may throw when the group provides none
     * @param ballotLease   the lease of proposals and ballots the tools write
     * @param settleTimeout how long {@code read_estimate} waits for an estimate to settle
     * @param fetchTimeout  how long {@code fetch_content} waits for an artifact
     * @param json          the JSON mapper results are written with
     * @param enabled       the tool names {@link #toolCallbacks()} exposes
     */
    public FleetCapabilityTools(Supplier<VoteClient> vote, Supplier<AggregateClient> aggregate,
                                Supplier<Content> content, Duration ballotLease, Duration settleTimeout,
                                Duration fetchTimeout, JsonMapper json, Set<String> enabled) {
        this.vote = Objects.requireNonNull(vote, "vote");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
        this.content = Objects.requireNonNull(content, "content");
        this.ballotLease = Objects.requireNonNull(ballotLease, "ballotLease");
        this.settleTimeout = Objects.requireNonNull(settleTimeout, "settleTimeout");
        this.fetchTimeout = Objects.requireNonNull(fetchTimeout, "fetchTimeout");
        this.json = Objects.requireNonNull(json, "json");
        this.enabled = Set.copyOf(enabled);
    }

    /** The tool names the two switches enable. */
    public static Set<String> enabled(boolean vote, boolean aggregate) {
        return enabled(vote, aggregate, false);
    }

    /** The tool names the three switches enable. */
    public static Set<String> enabled(boolean vote, boolean aggregate, boolean content) {
        Set<String> names = new LinkedHashSet<>();
        if (vote) {
            names.addAll(VOTE_TOOLS);
        }
        if (aggregate) {
            names.addAll(AGGREGATE_TOOLS);
        }
        if (content) {
            names.addAll(CONTENT_TOOLS);
        }
        return names;
    }

    /**
     * The enabled tools, to attach to a ChatClient. Like {@code FleetTools},
     * this bean is not a {@code ToolCallbackProvider} itself, so Spring AI's MCP
     * server never publishes it implicitly.
     *
     * @return the enabled tools
     */
    public ToolCallbackProvider toolCallbacks() {
        return new FleetCapabilityToolCallbackProvider(this, enabled);
    }

    /** Whether a tool is switched on. */
    public boolean enabled(String toolName) {
        return enabled.contains(toolName);
    }

    /**
     * Opens a vote, once per proposal id.
     *
     * @param id       the proposal's id
     * @param question the question
     * @param options  the options, at least two
     * @param quorum   the distinct voters that close the vote
     * @return the proposal as JSON, or an error
     */
    @Tool(name = "propose_vote", description = "Opens a vote in the fleet: a proposal with an id, a question,"
            + " the options to choose from, and the number of distinct voters that closes it. Idempotent on"
            + " the id: proposing an id that is already open returns the open proposal.")
    public String proposeVote(@ToolParam(description = "A short unique id for the proposal") String id,
                              @ToolParam(description = "The question the fleet votes on") String question,
                              @ToolParam(description = "The options, at least two") List<String> options,
                              @ToolParam(description = "How many distinct voters close the vote") int quorum) {
        return withVote(client -> {
            if (client.proposal(id).isEmpty()) {
                client.propose(id, question, options, quorum, Lease.of(ballotLease));
            }
            return render(proposal(client.proposal(id).orElseThrow()));
        });
    }

    /**
     * Casts this agent's ballot.
     *
     * @param id     the proposal's id
     * @param option the option, exactly as proposed
     * @return what was cast, or an error
     */
    @Tool(name = "cast_ballot", description = "Casts this agent's ballot on an open proposal; one ballot per"
            + " proposal counts. The option must be one of the proposal's options, exactly as proposed.")
    public String castBallot(@ToolParam(description = "The proposal's id") String id,
                             @ToolParam(description = "The option, exactly as proposed") String option) {
        return withVote(client -> {
            Optional<VoteCapability.Proposal> proposal = client.proposal(id);
            if (proposal.isEmpty()) {
                return error("no proposal '" + id + "' is open here");
            }
            if (!proposal.get().options().contains(option)) {
                return error("'" + option + "' is not one of " + proposal.get().options());
            }
            client.castBallot(id, option, Lease.of(ballotLease));
            return render(Map.of("proposalId", id, "cast", option));
        });
    }

    /**
     * The votes per option so far.
     *
     * @param id the proposal's id
     * @return the tally as JSON, or an error
     */
    @Tool(name = "read_tally", description = "The votes per option on a proposal so far.")
    public String readTally(@ToolParam(description = "The proposal's id") String id) {
        return withVote(client -> render(Map.of("proposalId", id, "tally", client.tally(id))));
    }

    /**
     * The decision, once the quorum closed the vote.
     *
     * @param id the proposal's id
     * @return the winner and tally, or that the vote is open, or an error
     */
    @Tool(name = "read_decision", description = "The decision of a proposal once its quorum closed it: the"
            + " winner and the tally; or that it is still open.")
    public String readDecision(@ToolParam(description = "The proposal's id") String id) {
        return withVote(client -> {
            Optional<VoteCapability.Decision> decision = client.decision(id);
            if (decision.isEmpty()) {
                return render(Map.of("proposalId", id, "open", true));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("proposalId", id);
            out.put("winner", decision.get().winner());
            out.put("tally", decision.get().tally());
            return render(out);
        });
    }

    /**
     * Contributes this peer's value to an epoch.
     *
     * @param epoch the epoch id every contributor shares
     * @param value this peer's value
     * @return what was contributed, or an error
     */
    @Tool(name = "contribute", description = "Contributes this peer's value to a fleet-wide average under an"
            + " epoch id; every peer that contributes under the same id is averaged together.")
    public String contribute(@ToolParam(description = "The epoch id, shared by every contributor") String epoch,
                             @ToolParam(description = "This peer's value") double value) {
        return withAggregate(client -> {
            client.start(epoch, value);
            return render(Map.of("epoch", epoch, "contributed", value));
        });
    }

    /**
     * The fleet-wide average under an epoch, waiting briefly for it to settle.
     *
     * @param epoch the epoch id
     * @return the estimate and whether it settled, or that nothing is known, or an error
     */
    @Tool(name = "read_estimate", description = "The fleet-wide average under an epoch id, waiting briefly"
            + " for it to settle; or that nothing is known yet.")
    public String readEstimate(@ToolParam(description = "The epoch id") String epoch) {
        return withAggregate(client -> {
            OptionalDouble settled = client.awaitSettled(epoch, PushSumAggregate.Settle.after(2, 0.01),
                    settleTimeout);
            OptionalDouble value = settled.isPresent() ? settled : client.estimate(epoch);
            if (value.isEmpty()) {
                return render(Map.of("epoch", epoch, "known", false));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("epoch", epoch);
            out.put("estimate", value.getAsDouble());
            out.put("settled", settled.isPresent());
            return render(out);
        });
    }

    // ---- the content family (srad/large-networks-bulk-data.md §7.2)

    /**
     * Publishes a file as an artifact.
     *
     * @param path the file on this peer
     * @param name a display name, or empty
     * @return the manifest's root, name, size, and pieces, or an error
     */
    @Tool(name = "publish_content", description = "Publishes a file from this peer's disk as a fleet artifact:"
            + " hashed in pieces, announced to the group, and served to every member that fetches it. Returns"
            + " the root, the content id other members fetch by.")
    public String publishContent(@ToolParam(description = "The path of the file on this peer") String path,
                                 @ToolParam(description = "A display name for the artifact, or empty", required = false)
                                 String name) {
        return withContent(swarm -> {
            try {
                PublishOptions options = swarm.publishOptions();
                if (name != null && !name.isBlank()) {
                    options = options.withName(name);
                }
                return render(manifest(swarm.publish(Path.of(path), options)));
            } catch (IOException | RuntimeException e) {
                return error("publish failed: " + e.getMessage());
            }
        });
    }

    /**
     * Fetches an artifact from every holder.
     *
     * @param root the artifact's root
     * @return the progress, or an error
     */
    @Tool(name = "fetch_content", description = "Fetches a fleet artifact by its root from every member that"
            + " holds it, waiting up to the configured timeout; returns how much arrived and whether it is complete.")
    public String fetchContent(@ToolParam(description = "The artifact's root (a content id starting with b)") String root) {
        return withContent(swarm -> {
            try {
                Fetch fetch = swarm.fetch(root);
                boolean complete = fetch.await(fetchTimeout);
                return render(progress(root, fetch, complete));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted");
            } catch (RuntimeException e) {
                return error("fetch failed: " + e.getMessage());
            }
        });
    }

    /**
     * Reads a fetched artifact as text.
     *
     * @param root     the artifact's root
     * @param maxChars the most characters to return
     * @return the text, or an error
     */
    @Tool(name = "read_content", description = "Reads a fetched fleet artifact as UTF-8 text, up to a number of"
            + " characters; fetch it first.")
    public String readContent(@ToolParam(description = "The artifact's root") String root,
                              @ToolParam(description = "The most characters to return") int maxChars) {
        return withContent(swarm -> {
            try {
                Fetch fetch = swarm.fetch(root);
                if (!fetch.complete()) { // await(ZERO) waits without bound; a read never blocks
                    return render(Map.of("root", root, "complete", false, "error", "not fetched yet; call fetch_content"));
                }
                try (SeekableByteChannel channel = fetch.open()) {
                    int bound = (int) Math.min(Math.max(0, maxChars) * 4L, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(bound);
                    while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                        // drain
                    }
                    String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("root", root);
                    out.put("size", channel.size());
                    out.put("text", text.length() > maxChars ? text.substring(0, Math.max(0, maxChars)) : text);
                    out.put("truncated", text.length() > maxChars || bound < channel.size());
                    return render(out);
                }
            } catch (IOException | RuntimeException e) {
                return error("read failed: " + e.getMessage());
            }
        });
    }

    /**
     * Describes an artifact.
     *
     * @param root the artifact's root
     * @return the manifest and the holders, or that it is unknown
     */
    @Tool(name = "describe_content", description = "What the fleet knows about an artifact: its manifest and how"
            + " many members hold it in full or in part.")
    public String describeContent(@ToolParam(description = "The artifact's root") String root) {
        return withContent(swarm -> {
            Optional<Content.Description> described = swarm.describe(root);
            if (described.isEmpty()) {
                return render(Map.of("root", root, "known", false));
            }
            Map<String, Object> out = manifest(described.get().manifest());
            out.put("completeHolders", described.get().completeHolders());
            out.put("partialHolders", described.get().partialHolders());
            out.put("heldHere", described.get().held().cardinality());
            return render(out);
        });
    }

    /**
     * Lists the group's artifacts.
     *
     * @return root, name, size, pieces, and media type of each
     */
    @Tool(name = "list_content", description = "The artifacts announced in this group: root, name, size, and"
            + " media type of each.")
    public String listContent() {
        return withContent(swarm -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Manifest m : swarm.list(100)) {
                out.add(manifest(m));
            }
            return render(out);
        });
    }

    private static Map<String, Object> manifest(Manifest m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", m.root());
        out.put("name", m.name());
        out.put("size", m.size());
        out.put("pieces", m.pieces());
        out.put("mediaType", m.mediaType());
        return out;
    }

    private static Map<String, Object> progress(String root, Fetch fetch, boolean complete) {
        Fetch.Progress p = fetch.progress();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", root);
        out.put("complete", complete);
        out.put("piecesVerified", p.piecesVerified());
        out.put("pieces", p.pieces());
        out.put("bytesReceived", p.bytesReceived());
        out.put("sources", p.bytesBySource().size());
        return out;
    }

    private String withContent(Function<Content, String> body) {
        Content swarm;
        try {
            swarm = content.get();
        } catch (RuntimeException e) {
            return error("the content swarm is not provided on this peer: " + e.getMessage());
        }
        return body.apply(swarm);
    }

    private String withVote(Function<VoteClient, String> body) {
        VoteClient client;
        try {
            client = vote.get();
        } catch (RuntimeException e) {
            return error("the vote capability is not provided on this peer: " + e.getMessage());
        }
        return body.apply(client);
    }

    private String withAggregate(Function<AggregateClient, String> body) {
        AggregateClient client;
        try {
            client = aggregate.get();
        } catch (RuntimeException e) {
            return error("the aggregate capability is not provided on this peer: " + e.getMessage());
        }
        return body.apply(client);
    }

    private static Map<String, Object> proposal(VoteCapability.Proposal p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", p.proposalId());
        out.put("question", p.question());
        out.put("options", p.options());
        out.put("quorum", p.quorum());
        return out;
    }

    private String error(String message) {
        return render(Map.of("error", message));
    }

    private String render(Object value) {
        return json.writeValueAsString(value);
    }
}
