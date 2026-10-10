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
package ai.badmonkey.agentspaces.springai;

import ai.badmonkey.agentspaces.agent.AgentSpaces;
import ai.badmonkey.agentspaces.agent.Entries;
import ai.badmonkey.agentspaces.agent.Tagged;
import ai.badmonkey.agentspaces.agent.annotation.Ballot;
import ai.badmonkey.agentspaces.agent.annotation.BidFunction;
import ai.badmonkey.agentspaces.agent.annotation.CapabilityRef;
import ai.badmonkey.agentspaces.agent.annotation.OnDecision;
import ai.badmonkey.agentspaces.agent.annotation.OnEstimate;
import ai.badmonkey.agentspaces.agent.annotation.Part;
import ai.badmonkey.agentspaces.agent.annotation.Propose;
import ai.badmonkey.agentspaces.agent.annotation.SpaceJoin;
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify;
import ai.badmonkey.agentspaces.agent.annotation.SpaceReduce;
import ai.badmonkey.agentspaces.agent.annotation.SpaceRef;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.agent.capability.Contribution;
import ai.badmonkey.agentspaces.agent.capability.Motion;
import ai.badmonkey.agentspaces.agent.capability.VoteClient;
import ai.badmonkey.agentspaces.agent.join.Joined;
import ai.badmonkey.agentspaces.agent.reduce.Reductions;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.SpaceEvent;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.spring.SpaceAgent;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The starter hands every {@code @SpaceAgent} bean to the core's
 * {@code AgentBinder} and this project adds nothing in between, so the 0.3.0
 * annotation surface should work in a Spring AI application unchanged. This
 * test proves it: two Spring Boot applications in one group over TCP, each
 * binding annotated beans, with every outcome read back from a space.
 */
class AnnotationPassthroughTest {

    private static final String FOUNDING = "springai-annotation-passthrough-v1";
    private static final Lease FIVE_MINUTES = Lease.of(Duration.ofMinutes(5));

    public record Ping(String id) {
    }

    public record Echoed(String id, String by) {
    }

    public record Fork(String id) {
    }

    public record Left(String id) {
    }

    public record Right(String id) {
    }

    public record Blink(String id) {
    }

    public record Lapsed(String id) {
    }

    public record Job(String id, double weight) {
    }

    public record Won(String id, double bid) {
    }

    public record Ask(String id) {
    }

    public record Opened(String id) {
    }

    public record Move(String id) {
    }

    public record Probe(String id) {
    }

    public record Checked(String id, String winner) {
    }

    public record Decided(String proposalId, String winner, String node) {
    }

    public record Reading(String node, String epoch, double value) {
    }

    public record Settled(String epochId, double value) {
    }

    public record Head(String id) {
    }

    public record Tail(String id) {
    }

    public record Assembled(String id, boolean withTail) {
    }

    public record Payment(String account, long cents) {
    }

    public record Balance(String account, long cents, int payments) {
    }

    /** {@code @SpaceNotify} on WRITTEN and EXPIRED, returning {@code Tagged} and an {@code Entries} fork. */
    @SpaceAgent(name = "echo", description = "Echoes cues as tagged entries, forks, and expiry notices")
    public static class Echo {
        @SpaceNotify(space = "cues", resultSpace = "results")
        public Tagged<Echoed> echo(Ping ping) {
            return Tagged.of(new Echoed(ping.id(), "echo"), "cue", ping.id());
        }

        @SpaceNotify(space = "cues", resultSpace = "results", produces = {Left.class, Right.class})
        public Entries fork(Fork fork) {
            return Entries.of(new Left(fork.id()), new Right(fork.id()));
        }

        @SpaceNotify(space = "cues", on = SpaceEvent.Kind.EXPIRED, resultSpace = "results")
        public Lapsed lapsed(Blink blink) {
            return new Lapsed(blink.id());
        }
    }

    /** {@code @BidFunction} prices an AUCTION space the same bean takes from. */
    @SpaceAgent(name = "bidder", description = "Prices jobs and runs the ones it wins")
    public static class Bidder {
        @BidFunction(space = "bids")
        public double bid(Job job) {
            return job.weight() * 2;
        }

        @SpaceTake(space = "bids", lease = "5s", pollTimeout = "300ms", resultSpace = "results")
        public Won run(Job job) {
            return new Won(job.id(), bid(job));
        }
    }

    /** {@code @Propose} opens a vote per cue; {@code @SpaceRef} hands the bean a space. */
    @SpaceAgent(name = "underwriter", description = "Puts every ask to the fleet")
    public static class Underwriter {
        @SpaceRef("results")
        Space results;

        @Propose(space = "cues", vote = "votes", prefix = "ask:", key = "id", options = {"yes", "no"},
                quorum = 2, lease = "5m")
        public String open(Ask ask) {
            results.write(new Opened(ask.id()), FIVE_MINUTES);
            return "Adopt " + ask.id() + "?";
        }
    }

    /** {@code @Ballot} casts the first option; {@code @OnDecision} records the winner. Bound on both apps. */
    @SpaceAgent(name = "panelist", description = "Casts for the first option and records decisions")
    public static class Panelist {
        private final String node;

        Panelist(String node) {
            this.node = Objects.requireNonNull(node, "node");
        }

        @Ballot(space = "votes", lease = "5m")
        public String judge(VoteCapability.Proposal proposal) {
            return proposal.options().get(0);
        }

        @OnDecision(space = "votes", resultSpace = "results")
        public Decided decided(VoteCapability.Decision decision) {
            return new Decided(decision.proposalId(), decision.winner(), node);
        }
    }

    /** A {@code Motion} return opens a vote; {@code @CapabilityRef} injects the client that reads it. */
    @SpaceAgent(name = "escalator", description = "Moves to escalate and checks what the fleet decided")
    public static class Escalator {
        @CapabilityRef
        VoteClient votes;

        @SpaceNotify(space = "cues")
        public Motion move(Move move) {
            return Motion.of("motion:" + move.id(), "Escalate " + move.id() + "?", List.of("go", "stay"), 2,
                    FIVE_MINUTES);
        }

        @SpaceNotify(space = "cues", resultSpace = "results")
        public Checked check(Probe probe) {
            return new Checked(probe.id(), votes.decision("motion:" + probe.id())
                    .map(VoteCapability.Decision::winner).orElse("undecided"));
        }
    }

    /** A {@code Contribution} return feeds push-sum; {@code where} keeps each app to its own readings. */
    @SpaceAgent(name = "alpha-sensor", description = "Contributes alpha's readings")
    public static class AlphaSensor {
        @SpaceNotify(space = "readings", where = "node=alpha")
        public Contribution sense(Reading reading) {
            return Contribution.to("load:" + reading.epoch(), reading.value());
        }
    }

    @SpaceAgent(name = "beta-sensor", description = "Contributes beta's readings")
    public static class BetaSensor {
        @SpaceNotify(space = "readings", where = "node=beta")
        public Contribution sense(Reading reading) {
            return Contribution.to("load:" + reading.epoch(), reading.value());
        }
    }

    /** {@code @OnEstimate} fires once the epoch settles. */
    @SpaceAgent(name = "supervisor", description = "Reports the settled fleet load")
    public static class Supervisor {
        @OnEstimate(prefix = "load:", settleTicks = 10, tolerance = 0.01, resultSpace = "results")
        public Settled settled(PushSumAggregate.Estimate estimate) {
            return new Settled(estimate.epochId(), estimate.value());
        }
    }

    /** {@code @SpaceJoin} in LOCAL mode over a required part and an optional one. */
    @SpaceAgent(name = "assembler", description = "Joins a head with its tail when one exists")
    public static class Assembler {
        @SpaceJoin(space = "parts", key = "id", resultSpace = "results",
                parts = {@Part(Head.class), @Part(value = Tail.class, optional = true)})
        public Assembled assemble(Joined joined) {
            return new Assembled(joined.key(), joined.find(Tail.class).isPresent());
        }
    }

    /** {@code @SpaceReduce} in LEASED mode folds payments into one balance per account. */
    @SpaceAgent(name = "teller", description = "Keeps a running balance per account")
    public static class Teller {
        @SpaceReduce(space = "ledger", key = "account", name = "balance", lease = "5s", pollTimeout = "300ms")
        public Balance fold(Balance balance, Payment payment) {
            long cents = (balance == null ? 0 : balance.cents()) + payment.cents();
            int count = (balance == null ? 0 : balance.payments()) + 1;
            return new Balance(payment.account(), cents, count);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Alpha {
        @Bean
        Echo echo() {
            return new Echo();
        }

        @Bean
        Bidder bidder() {
            return new Bidder();
        }

        @Bean
        Underwriter underwriter() {
            return new Underwriter();
        }

        @Bean
        Panelist panelist() {
            return new Panelist("alpha");
        }

        @Bean
        Escalator escalator() {
            return new Escalator();
        }

        @Bean
        AlphaSensor alphaSensor() {
            return new AlphaSensor();
        }

        @Bean
        Supervisor supervisor() {
            return new Supervisor();
        }

        @Bean
        Assembler assembler() {
            return new Assembler();
        }

        @Bean
        Teller teller() {
            return new Teller();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Beta {
        @Bean
        Panelist panelist() {
            return new Panelist("beta");
        }

        @Bean
        BetaSensor betaSensor() {
            return new BetaSensor();
        }
    }

    private static ConfigurableApplicationContext app(Class<?> config, int bindPort, int seed) {
        return new SpringApplicationBuilder(config)
                .properties(FleetApps.nodeProperties(bindPort, FOUNDING, seed,
                        "bids", "cues", "results", "readings", "parts", "ledger", "votes"))
                .properties(Map.of("agentspaces.groups[0].spaces[0].strategy", "AUCTION",
                        "agentspaces.capabilities.aggregate", "true",
                        "agentspaces.capabilities.vote", "true"))
                .run();
    }

    @Test
    @Timeout(180)
    void everyAnnotationBindsThroughTheStarterUnchanged() throws Exception {
        int seed = TestPeer.freePort();
        try (ConfigurableApplicationContext alpha = app(Alpha.class, seed, 0);
             ConfigurableApplicationContext beta = app(Beta.class, TestPeer.freePort(), seed)) {
            Thread.sleep(1500); // membership settles
            AgentSpaces.GroupContext group = alpha.getBean(FleetContext.class).group();
            Space cues = group.space("cues");
            Space results = group.space("results");

            // @SpaceNotify on WRITTEN, returning Tagged: the entry lands with its tags.
            cues.write(new Ping("p1"), FIVE_MINUTES);
            Space.Entry<Echoed> echoed = awaitEntry(results, Template.of(Echoed.class));
            assertThat(echoed.value()).isEqualTo(new Echoed("p1", "echo"));
            assertThat(echoed.tags()).containsEntry("cue", "p1");

            // An Entries fork writes every element.
            cues.write(new Fork("f1"), FIVE_MINUTES);
            assertThat(awaitOne(results, Template.of(Left.class))).isEqualTo(new Left("f1"));
            assertThat(awaitOne(results, Template.of(Right.class))).isEqualTo(new Right("f1"));

            // @SpaceNotify on EXPIRED: a one-second lease is the timer.
            cues.write(new Blink("b1"), Lease.of(Duration.ofSeconds(1)));
            assertThat(awaitOne(results, Template.of(Lapsed.class))).isEqualTo(new Lapsed("b1"));

            // @BidFunction prices the AUCTION space; the take carries the bid.
            group.space("bids").write(new Job("j1", 3.0), FIVE_MINUTES);
            assertThat(awaitOne(results, Template.of(Won.class))).isEqualTo(new Won("j1", 6.0));

            // @Propose opens ask:a1 through @SpaceRef; @Ballot on both apps meets quorum 2;
            // @OnDecision records the winner on each.
            cues.write(new Ask("a1"), FIVE_MINUTES);
            assertThat(awaitOne(results, Template.of(Opened.class))).isEqualTo(new Opened("a1"));
            assertThat(awaitOne(results, decided("ask:a1", "alpha"))).isEqualTo(new Decided("ask:a1", "yes", "alpha"));
            assertThat(awaitOne(results, decided("ask:a1", "beta"))).isEqualTo(new Decided("ask:a1", "yes", "beta"));

            // A Motion return opens motion:m1; @CapabilityRef's VoteClient reads the decision.
            cues.write(new Move("m1"), FIVE_MINUTES);
            assertThat(awaitOne(results, decided("motion:m1", "alpha"))).isEqualTo(new Decided("motion:m1", "go", "alpha"));
            cues.write(new Probe("m1"), FIVE_MINUTES);
            assertThat(awaitOne(results, Template.of(Checked.class))).isEqualTo(new Checked("m1", "go"));

            // Contribution returns feed one push-sum epoch; @OnEstimate settles at the fleet average.
            group.space("readings").write(new Reading("alpha", "e1", 10.0), FIVE_MINUTES);
            group.space("readings").write(new Reading("beta", "e1", 30.0), FIVE_MINUTES);
            Settled settled = awaitOne(results, Template.of(Settled.class));
            assertThat(settled.epochId()).isEqualTo("load:e1");
            assertThat(settled.value()).isCloseTo(20.0, within(1.0));

            // @SpaceJoin LOCAL: k1 joins with its optional tail, k2 without one.
            Space parts = group.space("parts");
            parts.write(new Tail("k1"), FIVE_MINUTES);
            parts.write(new Head("k1"), FIVE_MINUTES);
            parts.write(new Head("k2"), FIVE_MINUTES);
            assertThat(awaitOne(results, Template.of(Assembled.class).where("id", eq("k1"))))
                    .isEqualTo(new Assembled("k1", true));
            assertThat(awaitOne(results, Template.of(Assembled.class).where("id", eq("k2"))))
                    .isEqualTo(new Assembled("k2", false));

            // @SpaceReduce LEASED: two payments fold into one accumulator.
            Space ledger = group.space("ledger");
            ledger.write(new Payment("acct", 5), FIVE_MINUTES);
            ledger.write(new Payment("acct", 7), FIVE_MINUTES);
            Balance balance = await(() -> Reductions.current(ledger, Balance.class, "balance", "acct")
                    .map(Space.Entry::value).filter(b -> b.payments() == 2).orElse(null));
            assertThat(balance).isEqualTo(new Balance("acct", 12, 2));
        }
    }

    private static Template<Decided> decided(String proposalId, String node) {
        return Template.of(Decided.class).where("proposalId", eq(proposalId)).where("node", eq(node));
    }

    private static <T> T awaitOne(Space space, Template<T> template) throws InterruptedException {
        return await(() -> space.readAll(template, 10).stream().findFirst().orElse(null));
    }

    private static <T> Space.Entry<T> awaitEntry(Space space, Template<T> template) throws InterruptedException {
        return await(() -> space.readAllEntries(template, 10).stream().findFirst().orElse(null));
    }

    private static <T> T await(Supplier<T> probe) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (true) {
            T value = probe.get();
            if (value != null) {
                return value;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("nothing arrived within 40 seconds");
            }
            Thread.sleep(200);
        }
    }
}
