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
package ai.badmonkey.agentspaces.examples.springai.patterns;

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.Ballot;
import ai.badmonkey.agentspaces.agent.annotation.OnDecision;
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify;
import ai.badmonkey.agentspaces.agent.capability.Motion;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.Evaluator;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Pattern: a panel of model judges. A {@link Claim} written to the votes space
 * is the cue: the lead's {@code @SpaceNotify} method returns a {@link Motion},
 * and the binder opens the vote as the lead, once per claim (ISSUE-Motion).
 * Each judge is an ordinary {@code @Ballot} agent whose vote comes from a
 * Spring AI {@link Evaluator} (a {@code FactCheckingEvaluator} over the
 * judge's own model) checking the claim against its evidence; the lead's
 * {@code @OnDecision} method records the verdict once the quorum closes. Every
 * ballot is a signed entry, so the panel's reasoning is auditable from the
 * space: which judge, on which peer, voted how.
 */
public final class JudgePanel {

    /** Separates a proposal's claim from its evidence in the question text. */
    public static final String EVIDENCE = "\n---\n";

    /** A claim put to the panel, with the evidence the judges check it against. */
    public record Claim(String id, String claim, String evidence) {
    }

    /** The recorded verdict. */
    public record Verdict(String proposalId, String winner, String tally) {
    }

    /** A judge: fact-checks the claim with its evaluator and votes. */
    @AgentSpec(name = "judge", description = "Fact-checks claims against evidence", goals = {"judge claims"})
    public static final class Judge {
        private final Evaluator evaluator;

        /**
         * Creates a judge.
         *
         * @param evaluator the judge's evaluator
         */
        public Judge(Evaluator evaluator) {
            this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        }

        /**
         * Votes on one claim.
         *
         * @param proposal the claim, with its evidence after {@link #EVIDENCE}
         * @return approve or reject
         */
        @Ballot(space = "votes", prefix = "claim:", lease = "10m")
        public String judge(VoteCapability.Proposal proposal) {
            String[] parts = proposal.question().split(EVIDENCE, 2);
            boolean holds = evaluator.evaluate(new EvaluationRequest(
                    List.of(new Document(parts.length > 1 ? parts[1] : "")), parts[0])).isPass();
            return holds ? "approve" : "reject";
        }
    }

    /** The lead: opens a vote for each claim and records each closed one. */
    @AgentSpec(name = "lead", description = "Puts claims to the panel and records its verdicts",
            goals = {"open votes", "record verdicts"})
    public static final class Lead {
        private final int quorum;

        /**
         * Creates the lead.
         *
         * @param quorum the ballots that close a claim
         */
        public Lead(int quorum) {
            this.quorum = quorum;
        }

        /**
         * Opens the vote on a claim: the returned motion is the proposal, and the
         * binder opens it once per claim id, as this agent.
         *
         * @param claim the claim
         * @return the motion
         */
        @SpaceNotify(space = "votes", lease = "1h")
        public Motion open(Claim claim) {
            return Motion.in("votes", "claim:" + claim.id(), question(claim.claim(), claim.evidence()),
                    List.of("approve", "reject"), quorum, Lease.of(Duration.ofMinutes(10)));
        }

        /**
         * Records a decision.
         *
         * @param decision the closed vote
         * @return the verdict entry
         */
        @OnDecision(space = "votes", prefix = "claim:", resultLease = "1h")
        public Verdict record(VoteCapability.Decision decision) {
            return new Verdict(decision.proposalId(), decision.winner(), decision.tally().toString());
        }
    }

    /**
     * The proposal question for a claim and its evidence.
     *
     * @param claim    the claim
     * @param evidence the evidence
     * @return the question text
     */
    public static String question(String claim, String evidence) {
        return claim + EVIDENCE + evidence;
    }

    private JudgePanel() {
    }
}
