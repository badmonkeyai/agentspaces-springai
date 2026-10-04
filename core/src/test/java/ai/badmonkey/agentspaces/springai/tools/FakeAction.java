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

import ai.badmonkey.agentspaces.common.id.AgentId;
import ai.badmonkey.agentspaces.common.id.PeerId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A fleet action for unit tests: no fleet, a function in its place. */
class FakeAction implements FleetAction {

    record Brief(String topic, int words) {
    }

    record Summary(String topic, String text) {
    }

    private static final PeerIdentity PEER = PeerIdentity.generate();

    final String agent;
    final boolean attested;
    final Function<Object, Optional<Object>> behavior;
    final List<Object> inputs = new CopyOnWriteArrayList<>();

    FakeAction(String agent, boolean attested, Function<Object, Optional<Object>> behavior) {
        this.agent = agent;
        this.attested = attested;
        this.behavior = behavior;
    }

    static FakeAction summarizer() {
        return new FakeAction("summarizer", false, in -> {
            Brief brief = (Brief) in;
            return Optional.of(new Summary(brief.topic(), "summary of " + brief.topic()));
        });
    }

    @Override
    public String name() {
        return agent + "_Brief";
    }

    @Override
    public String description() {
        return "Summarizes briefs";
    }

    @Override
    public List<String> goals() {
        return List.of("summarize");
    }

    @Override
    public AgentId agent() {
        return PEER.agent(agent);
    }

    @Override
    public PeerId issuer() {
        return PEER.peerId();
    }

    @Override
    public boolean attested() {
        return attested;
    }

    @Override
    public Class<?> inputType() {
        return Brief.class;
    }

    @Override
    public Class<?> outputType() {
        return Summary.class;
    }

    @Override
    public Optional<Object> invoke(Object input, Duration timeout) {
        inputs.add(input);
        return behavior.apply(input);
    }
}
