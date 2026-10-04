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
import ai.badmonkey.agentspaces.agent.annotation.SpaceRef;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * Pattern: an extraction ensemble. Extractors on different models react to
 * every scanned document with {@code @SpaceNotify} and write a candidate
 * reading with their confidence; voters back the most confident candidate with
 * {@code @Ballot}; and the filer's {@code @OnDecision} files the winner with the
 * tally as provenance. Example 08's pipeline, with real models in place of the
 * hand-written readers.
 */
public final class ExtractionEnsemble {

    /** A document to read. */
    public record ScanDoc(String docId, String text) {
    }

    /** One extractor's reading. */
    public record Candidate(String docId, String extractor, String value, double confidence) {
    }

    /** The filed reading. */
    public record Extraction(String docId, String value, String extractor, String tally) {
    }

    /** An extractor over one model. */
    @AgentSpec(name = "extractor", description = "Reads invoice totals", goals = {"extract"})
    public static final class Extractor {
        private final String name;
        private final ChatClient chat;
        private final double confidence;

        /**
         * Creates an extractor.
         *
         * @param name       the extractor's name
         * @param chat       its model
         * @param confidence how much its readings are trusted
         */
        public Extractor(String name, ChatClient chat, double confidence) {
            this.name = Objects.requireNonNull(name, "name");
            this.chat = Objects.requireNonNull(chat, "chat");
            this.confidence = confidence;
        }

        /**
         * Reads one document.
         *
         * @param doc the document
         * @return the candidate reading
         */
        @SpaceNotify(space = "intake", lease = "1h", resultLease = "10m")
        public Candidate extract(ScanDoc doc) {
            String value = chat.prompt().user("Extract the invoice total from: " + doc.text()).call().content();
            return new Candidate(doc.docId(), name, value.strip(), confidence);
        }
    }

    /** A voter: backs the most confident candidate for the proposal's document. */
    @AgentSpec(name = "voter", description = "Backs the most confident reading", goals = {"vote"})
    public static final class Voter {
        @SpaceRef("intake")
        Space intake;

        /**
         * Votes.
         *
         * @param proposal "which reading of doc:ID do we file?"
         * @return the chosen extractor, or null to abstain
         */
        @Ballot(space = "intake", prefix = "doc:", lease = "10m")
        public String back(VoteCapability.Proposal proposal) {
            String docId = proposal.proposalId().substring("doc:".length());
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                List<Candidate> candidates = intake.readAll(
                        Template.of(Candidate.class).where("docId", eq(docId)), 10);
                if (candidates.size() >= proposal.options().size()) {
                    return candidates.stream().max(Comparator.comparingDouble(Candidate::confidence))
                            .orElseThrow().extractor();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }
    }

    /** The filer: files the reading the vote chose. */
    @AgentSpec(name = "filer", description = "Files the chosen reading", goals = {"file"})
    public static final class Filer {
        @SpaceRef("intake")
        Space intake;

        /**
         * Files the winner.
         *
         * @param decision the closed vote
         * @return the filed extraction
         */
        @OnDecision(space = "intake", prefix = "doc:", resultLease = "1h")
        public Extraction file(VoteCapability.Decision decision) {
            String docId = decision.proposalId().substring("doc:".length());
            Candidate chosen = intake.readAll(Template.of(Candidate.class).where("docId", eq(docId)), 10).stream()
                    .filter(c -> c.extractor().equals(decision.winner())).findFirst().orElseThrow();
            return new Extraction(docId, chosen.value(), chosen.extractor(), decision.tally().toString());
        }
    }

    private ExtractionEnsemble() {
    }
}
