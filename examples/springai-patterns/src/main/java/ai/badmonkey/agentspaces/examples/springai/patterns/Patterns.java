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

import ai.badmonkey.agentspaces.api.space.ConflictStrategyType;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * Runs the three patterns, each on its own small fleet over TCP. Run with
 * {@code mvn -q -pl examples/springai-patterns exec:java}.
 */
public final class Patterns {

    private static final Lease LEASE = Lease.of(Duration.ofMinutes(10));

    private Patterns() {
    }

    // ------------------------------------------------------------ judges

    /** A strict judge: the claim must appear in the document as written. */
    static String strict(String prompt) {
        return document(prompt).toLowerCase(Locale.ROOT).contains(claim(prompt).toLowerCase(Locale.ROOT)) ? "yes" : "no";
    }

    /** A careful judge: every content word of the claim must appear in the document. */
    static String careful(String prompt) {
        String document = document(prompt).toLowerCase(Locale.ROOT);
        return Arrays.stream(claim(prompt).toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(word -> word.length() > 3).allMatch(document::contains) ? "yes" : "no";
    }

    /** A lenient judge: agrees with everything. */
    static String lenient(String prompt) {
        return "yes";
    }

    private static String document(String prompt) {
        int start = prompt.indexOf("Document:");
        int end = prompt.indexOf("Claim:");
        return start < 0 || end < 0 ? "" : prompt.substring(start + "Document:".length(), end).strip();
    }

    private static String claim(String prompt) {
        int start = prompt.indexOf("Claim:");
        return start < 0 ? "" : prompt.substring(start + "Claim:".length()).strip();
    }

    /**
     * A panel of three judges decides two claims.
     *
     * @param ports three free ports
     * @return the verdicts, true claim first
     */
    public static List<JudgePanel.Verdict> judges(int... ports) throws Exception {
        String founding = "springai-patterns-judges-" + ports[0];
        List<Function<String, String>> models = List.of(Patterns::strict, Patterns::careful, Patterns::lenient);
        List<PatternPeer> panel = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                PatternPeer peer = PatternPeer.start(founding, "judge-" + i, ports[i], i == 0 ? 0 : ports[0],
                        ConflictStrategyType.LEASE_RACE, "votes");
                peer.vote("votes");
                peer.group().bind(new JudgePanel.Judge(
                        FactCheckingEvaluator.builder(ScriptedModels.answering(models.get(i))).build()));
                panel.add(peer);
            }
            PatternPeer chair = panel.get(0);
            chair.group().bind(new JudgePanel.Lead(3));
            Thread.sleep(1500);
            // Each claim is a cue: the lead's Motion return opens its vote.
            Space votes = chair.spaces().get("votes");
            votes.write(new JudgePanel.Claim("leases", "leases make crashed work reappear",
                    "Leases make crashed work reappear for another worker."), LEASE);
            votes.write(new JudgePanel.Claim("gossip", "gossip requires a central server",
                    "Gossip spreads state between peers with no central server."), LEASE);
            return List.of(await(() -> votes.read(Template.of(JudgePanel.Verdict.class)
                            .where("proposalId", eq("claim:leases"))).orElse(null)),
                    await(() -> votes.read(Template.of(JudgePanel.Verdict.class)
                            .where("proposalId", eq("claim:gossip"))).orElse(null)));
        } finally {
            panel.forEach(PatternPeer::close);
        }
    }

    // ------------------------------------------------------------ token-priced auction

    /**
     * Two models bid for four tasks: a cheap mini model that handles routine
     * work, and a premium model for everything.
     *
     * @param ports three free ports
     * @return the results, by difficulty
     */
    public static List<TokenPricedAuction.ModelResult> auction(int... ports) throws Exception {
        String founding = "springai-patterns-auction-" + ports[0];
        try (PatternPeer desk = PatternPeer.start(founding, "desk", ports[0], 0, ConflictStrategyType.AUCTION, "model-tasks");
             PatternPeer mini = PatternPeer.start(founding, "mini", ports[1], ports[0], ConflictStrategyType.AUCTION, "model-tasks");
             PatternPeer premium = PatternPeer.start(founding, "premium", ports[2], ports[0], ConflictStrategyType.AUCTION, "model-tasks")) {
            mini.group().bind(new TokenPricedAuction.ModelWorker("mini",
                    ScriptedModels.answering(p -> "mini answer").build(), 0.15, 3), "mini");
            premium.group().bind(new TokenPricedAuction.ModelWorker("premium",
                    ScriptedModels.answering(p -> "premium answer").build(), 3.0, 10), "premium");
            Thread.sleep(1500);
            Space tasks = desk.spaces().get("model-tasks");
            int[] difficulties = {1, 2, 7, 9};
            for (int difficulty : difficulties) {
                tasks.write(new TokenPricedAuction.ModelTask("task of difficulty " + difficulty, difficulty), LEASE);
            }
            List<TokenPricedAuction.ModelResult> results = new ArrayList<>();
            for (int difficulty : difficulties) {
                results.add(await(() -> tasks.read(Template.of(TokenPricedAuction.ModelResult.class)
                        .where("prompt", eq("task of difficulty " + difficulty))).orElse(null)));
            }
            return results;
        }
    }

    // ------------------------------------------------------------ extraction ensemble

    /**
     * Two extractors read a smudged invoice; the vote files the more confident reading.
     *
     * @param ports three free ports
     * @return the filed extraction
     */
    public static ExtractionEnsemble.Extraction ensemble(int... ports) throws Exception {
        String founding = "springai-patterns-ensemble-" + ports[0];
        try (PatternPeer filer = PatternPeer.start(founding, "filer", ports[0], 0, ConflictStrategyType.LEASE_RACE, "intake");
             PatternPeer careful = PatternPeer.start(founding, "careful", ports[1], ports[0], ConflictStrategyType.LEASE_RACE, "intake");
             PatternPeer fast = PatternPeer.start(founding, "fast", ports[2], ports[0], ConflictStrategyType.LEASE_RACE, "intake")) {
            VoteCapability vote = filer.vote("intake");
            careful.vote("intake");
            fast.vote("intake");
            careful.group().bind(new ExtractionEnsemble.Extractor("careful",
                    ScriptedModels.answering(p -> "12,400.00").build(), 0.95));
            careful.group().bind(new ExtractionEnsemble.Voter());
            fast.group().bind(new ExtractionEnsemble.Extractor("fast",
                    ScriptedModels.answering(p -> "12,4O0.OO").build(), 0.6));
            fast.group().bind(new ExtractionEnsemble.Voter());
            filer.group().bind(new ExtractionEnsemble.Filer());
            Thread.sleep(1500);
            Space intake = filer.spaces().get("intake");
            intake.write(new ExtractionEnsemble.ScanDoc("inv-7", "smudged: total 12,4??.??"), LEASE);
            await(() -> intake.readAll(Template.of(ExtractionEnsemble.Candidate.class)
                    .where("docId", eq("inv-7")), 10).size() >= 2 ? Boolean.TRUE : null);
            vote.propose("doc:inv-7", "Which reading of inv-7 do we file?", List.of("careful", "fast"), 2, LEASE);
            return await(() -> intake.read(Template.of(ExtractionEnsemble.Extraction.class)
                    .where("docId", eq("inv-7"))).orElse(null));
        }
    }

    private static <T> T await(Supplier<T> probe) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            T value = probe.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(150);
        }
        throw new IllegalStateException("no result within 60 seconds");
    }

    static int[] freePorts(int count) throws IOException {
        int[] ports = new int[count];
        for (int i = 0; i < count; i++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                ports[i] = socket.getLocalPort();
            }
        }
        return ports;
    }

    /**
     * Runs all three patterns.
     *
     * @param args unused
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\njudges:");
        judges(freePorts(3)).forEach(v -> System.out.println("  " + v));
        System.out.println("token-priced auction:");
        auction(freePorts(3)).forEach(r -> System.out.printf("  %-24s -> %-8s (bid %.4f)%n", r.prompt(), r.worker(), r.price()));
        System.out.println("extraction ensemble:");
        System.out.println("  " + ensemble(freePorts(3)) + "\n");
    }
}
