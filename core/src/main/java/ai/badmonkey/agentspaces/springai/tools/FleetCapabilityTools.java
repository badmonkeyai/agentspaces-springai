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
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.springai.FleetContext;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
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

    private final Supplier<VoteClient> vote;
    private final Supplier<AggregateClient> aggregate;
    private final Duration ballotLease;
    private final Duration settleTimeout;
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
        this(() -> fleet.group().capability(VoteClient.class),
                () -> fleet.group().capability(AggregateClient.class),
                ballotLease, settleTimeout, json, enabled(vote, aggregate));
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
        this.vote = Objects.requireNonNull(vote, "vote");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
        this.ballotLease = Objects.requireNonNull(ballotLease, "ballotLease");
        this.settleTimeout = Objects.requireNonNull(settleTimeout, "settleTimeout");
        this.json = Objects.requireNonNull(json, "json");
        this.enabled = Set.copyOf(enabled);
    }

    /** The tool names the two switches enable. */
    public static Set<String> enabled(boolean vote, boolean aggregate) {
        Set<String> names = new LinkedHashSet<>();
        if (vote) {
            names.addAll(VOTE_TOOLS);
        }
        if (aggregate) {
            names.addAll(AGGREGATE_TOOLS);
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
