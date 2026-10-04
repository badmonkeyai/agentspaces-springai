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
package ai.badmonkey.agentspaces.springai.support;

import ai.badmonkey.agentspaces.agent.AgentBinder;
import ai.badmonkey.agentspaces.api.ad.GroupAdvertisement;
import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.api.space.ConflictStrategyType;
import ai.badmonkey.agentspaces.common.codec.CborCodec;
import ai.badmonkey.agentspaces.common.id.GroupId;
import ai.badmonkey.agentspaces.discovery.AdCache;
import ai.badmonkey.agentspaces.discovery.DiscoveryService;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.peering.membership.GroupMembership;
import ai.badmonkey.agentspaces.peering.node.GroupRuntime;
import ai.badmonkey.agentspaces.peering.node.PeerNode;
import ai.badmonkey.agentspaces.peering.transport.TcpTransport;
import ai.badmonkey.agentspaces.space.replicated.ReplicatedSpace;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A plain AgentSpaces peer (no Spring) that joins the same group a Spring
 * application configures by founding string, over TCP: the stand-in for "an
 * agent on another machine" in the fleet tests.
 */
public final class TestPeer implements AutoCloseable {

    public final PeerIdentity identity;
    public final PeerNode node;
    public final GroupRuntime runtime;
    public final DiscoveryService discovery;
    public final Map<String, ReplicatedSpace> spaces;
    public final AgentBinder binder;

    private TestPeer(PeerIdentity identity, PeerNode node, GroupRuntime runtime,
                     DiscoveryService discovery, Map<String, ReplicatedSpace> spaces,
                     AgentBinder binder) {
        this.identity = identity;
        this.node = node;
        this.runtime = runtime;
        this.discovery = discovery;
        this.spaces = spaces;
        this.binder = binder;
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Starts a peer in the group derived from {@code founding}.
     *
     * @param founding   the founding string the Spring side configures
     * @param groupName  the group's name
     * @param port       the TCP port to listen on
     * @param seedPort   a member's port, or 0 for none
     * @param spaceNames the spaces to open, bound into the peer's binder
     */
    public static TestPeer start(String founding, String groupName, int port, int seedPort,
                                 String... spaceNames) throws Exception {
        PeerIdentity identity = PeerIdentity.generate();
        PeerNode node = PeerNode.builder(identity).build();
        node.listen(new TcpTransport(), "127.0.0.1:" + port);
        GroupId groupId = GroupId.fromFounding(founding.getBytes(StandardCharsets.UTF_8));
        GroupAdvertisement group = new GroupAdvertisement("aspace://" + groupId.value(),
                PeerIdentity.generate().peerId(), groupId, Instant.EPOCH, Duration.ofDays(365),
                groupName, GroupAdvertisement.MembershipPolicy.OPEN,
                ConflictStrategyType.LEASE_RACE, GroupAdvertisement.GossipParameters.defaults());
        List<PeerAdvertisement.Endpoint> seeds = seedPort == 0 ? List.of()
                : List.of(new PeerAdvertisement.Endpoint("tcp", "127.0.0.1:" + seedPort, 0));
        GroupRuntime runtime = node.joinGroup(group,
                new GroupMembership.Config(Duration.ofSeconds(30), Duration.ofSeconds(2), 2), seeds);
        CborCodec codec = CborCodec.defaultCodec();
        DiscoveryService discovery = new DiscoveryService(runtime,
                new AdCache(codec, InstantSource.system()), codec, identity.peerId());
        AgentBinder binder = new AgentBinder(identity, runtime.id(), discovery, InstantSource.system());
        Map<String, ReplicatedSpace> spaces = new LinkedHashMap<>();
        for (String name : spaceNames) {
            ReplicatedSpace space = ReplicatedSpace.builder(runtime, name, identity, "host")
                    .settleWindow(Duration.ofMillis(100))
                    .build();
            spaces.put(name, space);
            binder.space(name, space);
        }
        node.startTicking(Duration.ofMillis(200));
        return new TestPeer(identity, node, runtime, discovery, spaces, binder);
    }

    @Override
    public void close() {
        binder.close();
        spaces.values().forEach(ReplicatedSpace::close);
        node.close();
    }
}
