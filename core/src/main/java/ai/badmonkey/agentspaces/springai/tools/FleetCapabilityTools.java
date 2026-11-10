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
import ai.badmonkey.agentspaces.agent.content.StorePin;
import ai.badmonkey.agentspaces.agent.content.StoreReceipt;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.content.Content;
import ai.badmonkey.agentspaces.content.Fetch;
import ai.badmonkey.agentspaces.content.PublishOptions;
import ai.badmonkey.agentspaces.pdt.Pdt;
import ai.badmonkey.agentspaces.pdt.refs.Heads;
import ai.badmonkey.agentspaces.pdt.refs.Ref;
import ai.badmonkey.agentspaces.pdt.stores.Stores;
import ai.badmonkey.agentspaces.pdt.values.PMap;
import ai.badmonkey.agentspaces.planner.StoreChooser;
import ai.badmonkey.agentspaces.springai.FleetContext;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

    /**
     * The persistent-tree family's tool names (srad/agentspaces-pdt.md PD-C-09), with pin and status on the
     * content stores (PDT 2 phase 7, P2-C-04).
     */
    public static final List<String> PDT_TOOLS = List.of("pdt_read", "pdt_assoc", "pdt_history", "pdt_pin",
            "pdt_store_status");

    /** The most entries {@code pdt_read} returns for a whole map. */
    public static final int PDT_READ_ENTRIES = 200;

    private final Supplier<VoteClient> vote;
    private final Supplier<AggregateClient> aggregate;
    private final Supplier<Content> content;
    private final Supplier<Pdt> pdt;
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

    /**
     * Creates the tools over the fleet's group context with the content and persistent-tree
     * families too (srad/agentspaces-pdt.md PD-C-09).
     *
     * @param fleet         the fleet context
     * @param vote          whether the vote tools are on
     * @param aggregate     whether the aggregate tools are on
     * @param content       whether the content swarm tools are on
     * @param pdt           whether the persistent-tree tools are on ({@code agentspaces.pdt.enabled} must build a facade)
     * @param ballotLease   the lease of proposals and ballots the tools write
     * @param settleTimeout how long {@code read_estimate} waits for an estimate to settle
     * @param fetchTimeout  how long {@code fetch_content} waits for an artifact
     * @param json          the JSON mapper results are written with
     * @param pdts          resolves the group's {@link Pdt}; may throw when the group has none
     */
    public FleetCapabilityTools(FleetContext fleet, boolean vote, boolean aggregate, boolean content, boolean pdt,
                                Duration ballotLease, Duration settleTimeout, Duration fetchTimeout, JsonMapper json,
                                Supplier<Pdt> pdts) {
        this(() -> fleet.group().capability(VoteClient.class),
                () -> fleet.group().capability(AggregateClient.class),
                () -> contentOf(fleet), pdts,
                ballotLease, settleTimeout, fetchTimeout, json, enabled(vote, aggregate, content, pdt));
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
        this(vote, aggregate, content, () -> {
            throw new IllegalStateException("no persistent trees were given to these tools");
        }, ballotLease, settleTimeout, fetchTimeout, json, enabled);
    }

    /**
     * Creates the tools over explicit clients, the content swarm and the persistent trees included.
     *
     * @param vote          resolves the vote client; may throw when the group provides no vote
     * @param aggregate     resolves the aggregate client; may throw when the group provides none
     * @param content       resolves the content swarm; may throw when the group provides none
     * @param pdt           resolves the group's {@link Pdt}; may throw when the group has none
     * @param ballotLease   the lease of proposals and ballots the tools write
     * @param settleTimeout how long {@code read_estimate} waits for an estimate to settle
     * @param fetchTimeout  how long {@code fetch_content} waits for an artifact
     * @param json          the JSON mapper results are written with
     * @param enabled       the tool names {@link #toolCallbacks()} exposes
     */
    public FleetCapabilityTools(Supplier<VoteClient> vote, Supplier<AggregateClient> aggregate,
                                Supplier<Content> content, Supplier<Pdt> pdt, Duration ballotLease,
                                Duration settleTimeout, Duration fetchTimeout, JsonMapper json, Set<String> enabled) {
        this.vote = Objects.requireNonNull(vote, "vote");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
        this.content = Objects.requireNonNull(content, "content");
        this.pdt = Objects.requireNonNull(pdt, "pdt");
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
        return enabled(vote, aggregate, content, false);
    }

    /** The tool names the four switches enable. */
    public static Set<String> enabled(boolean vote, boolean aggregate, boolean content, boolean pdt) {
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
        if (pdt) {
            names.addAll(PDT_TOOLS);
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

    // ---- the persistent-tree family (srad/agentspaces-pdt.md PD-C-09)

    /**
     * Reads a persistent map ref at a path.
     *
     * @param ref  the ref's name
     * @param path keys separated by {@code /}, or empty for the whole map
     * @return the value as JSON, or an error
     */
    @Tool(name = "pdt_read", description = "Reads a persistent map ref of the fleet by name: the value at a path"
            + " of keys separated by /, or the whole map (up to 200 entries) when the path is empty. The value"
            + " is the merge every member computes from the ref's heads.")
    public String pdtRead(@ToolParam(description = "The ref's name") String ref,
                          @ToolParam(description = "A path of keys separated by /, or empty for the whole map",
                                  required = false) String path) {
        return withPdt(facade -> {
            PMap<Object, Object> map = mapRef(facade, ref).deref();
            List<Object> steps = pathOf(path);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ref", ref);
            out.put("root", map.root());
            out.put("path", path == null ? "" : path);
            if (steps.isEmpty()) {
                Map<Object, Object> entries = new LinkedHashMap<>();
                int n = 0;
                for (Map.Entry<Object, Object> e : map) {
                    if (n++ >= PDT_READ_ENTRIES) {
                        break;
                    }
                    entries.put(e.getKey(), e.getValue());
                }
                out.put("count", map.count());
                out.put("value", entries);
                out.put("truncated", map.count() > PDT_READ_ENTRIES);
                return render(out);
            }
            Optional<Object> top = map.get(steps.get(0));
            Object value = top.orElse(null);
            boolean found = top.isPresent();
            for (int i = 1; found && i < steps.size(); i++) {
                Object step = steps.get(i);
                if (value instanceof Map<?, ?> m) {
                    found = m.containsKey(step) || (step instanceof Integer && m.containsKey(String.valueOf(step)));
                    value = m.containsKey(step) ? m.get(step) : m.get(String.valueOf(step));
                } else if (value instanceof List<?> list && step instanceof Integer index) {
                    found = index >= 0 && index < list.size();
                    value = found ? list.get(index) : null;
                } else {
                    found = false;
                }
            }
            out.put("found", found);
            out.put("value", found ? value : null);
            return render(out);
        });
    }

    /**
     * Puts a value into a persistent map ref at a path, as a new version.
     *
     * @param ref   the ref's name
     * @param path  keys separated by {@code /}; the first is a key of the map
     * @param value the value as JSON, or plain text
     * @return the new version's root, or an error
     */
    @Tool(name = "pdt_assoc", description = "Puts a value into a persistent map ref of the fleet at a path of"
            + " keys separated by /, as a new version every member merges; the value is JSON (an object,"
            + " array, number, boolean, or null) or plain text. Returns the new version's root.")
    public String pdtAssoc(@ToolParam(description = "The ref's name") String ref,
                           @ToolParam(description = "A path of keys separated by /; the first is a key of the map") String path,
                           @ToolParam(description = "The value as JSON, or plain text") String value) {
        return withPdt(facade -> {
            List<Object> steps = pathOf(path);
            if (steps.isEmpty()) {
                return error("pdt_assoc needs a path of at least one key");
            }
            Object parsed = parseValue(value);
            PMap<Object, Object> written = mapRef(facade, ref).swap(map -> steps.size() == 1
                    ? map.assoc(steps.get(0), parsed)
                    : map.updateIn(steps, old -> parsed));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ref", ref);
            out.put("root", written.root());
            out.put("path", path);
            out.put("value", parsed);
            out.put("count", written.count());
            return render(out);
        });
    }

    /**
     * The versions of a persistent map ref.
     *
     * @param ref the ref's name
     * @param n   the most earlier versions to list
     * @return the heads then the earlier versions, newest first, or an error
     */
    @Tool(name = "pdt_history", description = "The versions of a persistent map ref of the fleet: the current"
            + " heads first, then up to n earlier versions newest first, each with its root, sequence number,"
            + " size, issuer, issue stamp, and the roots it was computed from.")
    public String pdtHistory(@ToolParam(description = "The ref's name") String ref,
                             @ToolParam(description = "The most earlier versions to list") int n) {
        return withPdt(facade -> {
            Ref<PMap<Object, Object>> handle = mapRef(facade, ref);
            Heads heads = handle.heads();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Heads.Head head : heads.heads()) {
                out.add(version(head, true));
            }
            for (Heads.Head earlier : handle.history(Math.max(0, n))) {
                out.add(version(earlier, false));
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("ref", ref);
            doc.put("heads", heads.heads().size());
            doc.put("versions", out);
            return render(doc);
        });
    }

    /**
     * Asks the group's content stores to hold a root (PDT 2 phase 7, P2-C-04; P2-S-11).
     *
     * @param root   a ref version's root or an artifact's root
     * @param copies how many stores should hold it
     * @param hours  how long they should hold it
     * @return the pin as JSON, or an error
     */
    @Tool(name = "pdt_pin", description = "Asks the fleet's content stores to hold a root, so the data outlives"
            + " this peer: a version root from pdt_read or pdt_assoc, or an artifact root from publish_content."
            + " The stores' cards decide which stores, and this peer signs the pin; pinning the same root again"
            + " renews it. Returns the stores asked. Fails when this peer does not hold the root or no store fits.")
    public String pdtPin(@ToolParam(description = "The root to keep (a content id starting with b)") String root,
                         @ToolParam(description = "How many stores should hold it, at least 1") int copies,
                         @ToolParam(description = "How long the stores should hold it, in hours, at least 1") int hours) {
        return withPdt(facade -> {
            if (hours < 1) {
                return error("pdt_pin needs hours of at least 1");
            }
            Stores.Pinning pinning = facade.storeFor(rootOf(root), StoreChooser.Need.of(0, copies), Duration.ofHours(hours));
            StorePin pin = pinning.pin();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("root", pin.root());
            out.put("kind", pin.kind());
            out.put("copies", pin.copies());
            out.put("leaseSeconds", pin.lease());
            out.put("stores", pinning.chosen().stream().map(card -> card.issuer().value()).toList());
            return render(out);
        });
    }

    /**
     * What the content stores hold of a root, as this peer sees it (PDT 2 phase 7, P2-C-04; P2-S-07).
     *
     * @param root the root
     * @return the receipts as JSON, or an error
     */
    @Tool(name = "pdt_store_status", description = "Which content stores hold a root, as this peer sees it: one"
            + " receipt per store with its bytes and when its hold ends, the copies that count (a store that"
            + " failed this peer's challenges no longer counts), and whether the root lost its store with no"
            + " other store to take it.")
    public String pdtStoreStatus(@ToolParam(description = "The root (a content id starting with b)") String root) {
        return withPdt(facade -> {
            String cid = rootOf(root);
            Stores stores = facade.stores();
            List<StoreReceipt> receipts = stores.receipts(cid);
            List<Map<String, Object>> holders = new ArrayList<>();
            for (StoreReceipt receipt : receipts) {
                Map<String, Object> holder = new LinkedHashMap<>();
                holder.put("store", receipt.holder().value());
                holder.put("bytes", receipt.bytes());
                holder.put("until", Instant.ofEpochMilli(receipt.until()).toString());
                holder.put("blind", receipt.blind());
                holders.add(holder);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("root", cid);
            out.put("copies", receipts.size());
            out.put("holders", holders);
            out.put("unplaced", stores.unplaced().contains(cid));
            return render(out);
        });
    }

    private static String rootOf(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("a root is needed");
        }
        return root.strip();
    }

    private static Ref<PMap<Object, Object>> mapRef(Pdt facade, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a ref name is needed");
        }
        return facade.ref(name, PMap.class);
    }

    /** The steps of a path: keys split on {@code /}, a decimal segment as an index for lists. */
    public static List<Object> pathOf(String path) {
        List<Object> steps = new ArrayList<>();
        if (path == null) {
            return steps;
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.chars().allMatch(Character::isDigit) && segment.length() < 10) {
                steps.add(Integer.parseInt(segment));
            } else {
                steps.add(segment);
            }
        }
        return steps;
    }

    /** JSON when the text parses as JSON, else the text itself. */
    Object parseValue(String value) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        if (text.isEmpty()) {
            return value;
        }
        char first = text.charAt(0);
        if (first == '{' || first == '[' || first == '"' || first == '-' || Character.isDigit(first)
                || text.equals("true") || text.equals("false") || text.equals("null")) {
            try {
                return json.readValue(text, Object.class);
            } catch (JacksonException e) {
                return value;
            }
        }
        return value;
    }

    private static Map<String, Object> version(Heads.Head head, boolean isHead) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", head.root());
        out.put("head", isHead);
        out.put("seq", head.seq());
        out.put("size", head.size());
        out.put("issuer", head.issuer() == null ? "" : head.issuer().toString());
        out.put("issued", head.issued() == null ? "" : head.issued().toString());
        out.put("base", head.base());
        return out;
    }

    private String withPdt(Function<Pdt, String> body) {
        Pdt facade;
        try {
            facade = pdt.get();
        } catch (RuntimeException e) {
            return error("the persistent trees are not provided on this peer: " + e.getMessage());
        }
        try {
            return body.apply(facade);
        } catch (RuntimeException e) {
            return error("pdt tool failed: " + e.getMessage());
        }
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
