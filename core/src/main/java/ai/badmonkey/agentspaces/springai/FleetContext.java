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
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.spi.Authorizer;
import ai.badmonkey.agentspaces.common.id.PeerId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;

import java.util.Objects;

/**
 * The fleet as this project sees it: one group of the node's {@link AgentSpaces},
 * the node's identity, and the profile's {@link Authorizer}. Every feature
 * resolves its spaces here, so a missing space fails at startup with the YAML
 * that fixes it, and every space the project uses is one the starter built,
 * with the group's block exchange, admission, and encryption.
 */
public class FleetContext {

    private final AgentSpaces spaces;
    private final String groupName;
    private final PeerIdentity identity;
    private final Authorizer authorizer;

    /**
     * Creates the context.
     *
     * @param spaces     the node's facade
     * @param groupName  the group this project works in
     * @param identity   the node's identity
     * @param authorizer the profile's authorizer
     */
    public FleetContext(AgentSpaces spaces, String groupName, PeerIdentity identity,
                        Authorizer authorizer) {
        this.spaces = Objects.requireNonNull(spaces, "spaces");
        this.groupName = Objects.requireNonNull(groupName, "groupName");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        if (!spaces.groupNames().contains(groupName)) {
            throw new IllegalStateException("agentspaces-springai works in group '" + groupName
                    + "', which this node has not joined; joined groups: " + spaces.groupNames()
                    + ". Set agentspaces.springai.group to one of them.");
        }
    }

    /** The node's facade. */
    public AgentSpaces spaces() {
        return spaces;
    }

    /** The group this project works in. */
    public AgentSpaces.GroupContext group() {
        return spaces.group(groupName);
    }

    /** The group's name. */
    public String groupName() {
        return groupName;
    }

    /** The node's identity. */
    public PeerIdentity identity() {
        return identity;
    }

    /** The node's PeerId. */
    public PeerId peerId() {
        return identity.peerId();
    }

    /** The profile's authorizer. */
    public Authorizer authorizer() {
        return authorizer;
    }

    /**
     * Resolves a configured space, or fails with the configuration that adds it.
     *
     * @param spaceName the space
     * @param property  the property that named it, for the error message
     * @return the space
     */
    public Space requiredSpace(String spaceName, String property) {
        AgentSpaces.GroupContext group = group();
        if (!group.spaceNames().contains(spaceName)) {
            throw new IllegalStateException("agentspaces-springai needs the space '" + spaceName
                    + "' (" + property + ") in group '" + groupName + "'. Add it under the group:\n"
                    + "  agentspaces:\n    groups:\n      - name: " + groupName + "\n        spaces:\n"
                    + "          - name: " + spaceName);
        }
        return group.space(spaceName);
    }
}
