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

import ai.badmonkey.agentspaces.agent.AgentSpaces;
import ai.badmonkey.agentspaces.api.ad.GroupAdvertisement;
import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.api.space.ConflictStrategyType;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.common.id.GroupId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.peering.membership.GroupMembership;
import ai.badmonkey.agentspaces.peering.node.GroupRuntime;
import ai.badmonkey.agentspaces.peering.node.PeerNode;
import ai.badmonkey.agentspaces.peering.transport.TcpTransport;
import ai.badmonkey.agentspaces.space.replicated.ReplicatedSpace;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One peer of a pattern's fleet: a node on TCP, a group joined by founding
 * string, the named spaces, and the {@link AgentSpaces} facade agents bind
 * through. Under Spring Boot the starter builds all of this from
 * {@code agentspaces.*} properties.
 *
 * @param name    the peer's name
 * @param node    the node
 * @param runtime the group runtime
 * @param group   the facade's group context
 * @param spaces  the spaces by name
 */
public record PatternPeer(String name, PeerNode node, GroupRuntime runtime, AgentSpaces.GroupContext group,
                          Map<String, ReplicatedSpace> spaces) implements AutoCloseable {

    /**
     * Starts a peer.
     *
     * @param founding the group's founding string
     * @param name     the peer's name
     * @param port     the TCP port
     * @param seedPort a member's port, or 0
     * @param strategy the spaces' conflict strategy
     * @param spaces   the spaces to open
     * @return the peer
     */
    public static PatternPeer start(String founding, String name, int port, int seedPort,
                                    ConflictStrategyType strategy, String... spaces) throws Exception {
        PeerIdentity identity = PeerIdentity.generate();
        PeerNode node = PeerNode.builder(identity).build();
        node.listen(new TcpTransport(), "127.0.0.1:" + port);
        GroupId groupId = GroupId.fromFounding(founding.getBytes(StandardCharsets.UTF_8));
        GroupAdvertisement ad = new GroupAdvertisement("aspace://" + groupId.value(),
                PeerIdentity.generate().peerId(), groupId, Instant.EPOCH, Duration.ofDays(365), founding,
                GroupAdvertisement.MembershipPolicy.OPEN, strategy, GroupAdvertisement.GossipParameters.defaults());
        List<PeerAdvertisement.Endpoint> seeds = seedPort == 0 ? List.of()
                : List.of(new PeerAdvertisement.Endpoint("tcp", "127.0.0.1:" + seedPort, 0));
        GroupRuntime runtime = node.joinGroup(ad,
                new GroupMembership.Config(Duration.ofSeconds(30), Duration.ofSeconds(2), 2), seeds);
        AgentSpaces facade = new AgentSpaces(identity, InstantSource.system());
        AgentSpaces.GroupContext group = facade.register(founding, runtime.id(), runtime, null);
        Map<String, ReplicatedSpace> opened = new LinkedHashMap<>();
        for (String space : spaces) {
            ReplicatedSpace replicated = ReplicatedSpace.builder(runtime, space, identity, name)
                    .settleWindow(Duration.ofMillis(200)).strategy(strategy).build();
            opened.put(space, replicated);
            group.space(space, replicated);
        }
        node.startTicking(Duration.ofMillis(200));
        return new PatternPeer(name, node, runtime, group, opened);
    }

    /**
     * Registers a vote over one of this peer's spaces, for {@code @Ballot} and
     * {@code @OnDecision} agents.
     *
     * @param space the vote space
     * @return the vote
     */
    public VoteCapability vote(String space) {
        ReplicatedSpace votes = spaces.get(space);
        VoteCapability vote = new VoteCapability(votes, votes.writer().orElseThrow(), node.peerId(),
                InstantSource.system());
        group.vote(space, vote);
        return vote;
    }

    @Override
    public void close() {
        group.binder().close();
        spaces.values().forEach(ReplicatedSpace::close);
        node.close();
    }
}
