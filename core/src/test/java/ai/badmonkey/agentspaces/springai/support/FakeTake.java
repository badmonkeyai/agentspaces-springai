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

import ai.badmonkey.agentspaces.agent.TakeContext;
import ai.badmonkey.agentspaces.api.entry.EntryId;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.identity.PeerIdentity;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** A take for unit tests: counts its renewals. */
public final class FakeTake implements TakenEntry<Object> {

    public final AtomicInteger renewals = new AtomicInteger();
    private final EntryId id = EntryId.newId();

    @Override
    public Object entry() {
        return "task";
    }

    @Override
    public EntryId entryId() {
        return id;
    }

    @Override
    public void renew(Duration extension) {
        renewals.incrementAndGet();
    }

    /** A take context over this take, for the given agent. */
    public TakeContext context(String agent) {
        return new TakeContext(this, Duration.ofMinutes(2), PeerIdentity.generate().agent(agent), "tasks");
    }
}
