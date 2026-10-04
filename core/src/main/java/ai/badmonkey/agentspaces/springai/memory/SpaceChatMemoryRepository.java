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
package ai.badmonkey.agentspaces.springai.memory;

import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.springai.model.WireMapping;
import ai.badmonkey.agentspaces.springai.model.wire.ConversationSnapshot;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;
import static ai.badmonkey.agentspaces.api.space.Matchers.lt;

/**
 * Spring AI chat memory in a replicated space, so a conversation outlives the
 * process that started it. Each save writes the conversation's next
 * {@link ConversationSnapshot} and retires the older versions, matching
 * {@code MessageWindowChatMemory}'s replace-the-window semantics; every snapshot
 * is leased, so abandoned conversations leave the space on their own; and a
 * snapshot over 64 KiB travels content-addressed over the group's block exchange.
 *
 * <p>Spring AI does not store tool-call intermediate messages in chat memory, so
 * a resumed conversation replays the user and assistant turns, and tools run
 * again if the model asks for them.
 */
public class SpaceChatMemoryRepository implements ChatMemoryRepository {

    private static final int READ_LIMIT = 10_000;
    private static final Lease RETIRE_LEASE = Lease.of(Duration.ofSeconds(30));

    private final Space space;
    private final Lease snapshotLease;

    /**
     * Creates the repository.
     *
     * @param space the space that holds the snapshots
     * @param ttl   each snapshot's lease
     */
    public SpaceChatMemoryRepository(Space space, Duration ttl) {
        this.space = Objects.requireNonNull(space, "space");
        this.snapshotLease = Lease.of(Objects.requireNonNull(ttl, "ttl"));
    }

    @Override
    public List<String> findConversationIds() {
        return space.readAll(Template.of(ConversationSnapshot.class), READ_LIMIT).stream()
                .map(ConversationSnapshot::conversationId).distinct().sorted().toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return latest(conversationId).map(snapshot -> WireMapping.fromWire(snapshot.messages()))
                .orElse(List.of());
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        long next = latest(conversationId).map(snapshot -> snapshot.version() + 1).orElse(1L);
        space.write(new ConversationSnapshot(conversationId, next, WireMapping.toWire(messages)), snapshotLease);
        retire(conversationId, next);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        retire(conversationId, Long.MAX_VALUE);
    }

    /** The conversation's newest snapshot on this replica. */
    public Optional<ConversationSnapshot> latest(String conversationId) {
        return space.readAll(of(conversationId), READ_LIMIT).stream()
                .max(Comparator.comparingLong(ConversationSnapshot::version));
    }

    private void retire(String conversationId, long below) {
        Template<ConversationSnapshot> older = of(conversationId).where("version", lt(below));
        while (true) {
            Optional<TakenEntry<ConversationSnapshot>> taken = space.take(older, RETIRE_LEASE, Duration.ZERO);
            if (taken.isEmpty()) {
                return;
            }
            space.complete(taken.get());
        }
    }

    private static Template<ConversationSnapshot> of(String conversationId) {
        return Template.of(ConversationSnapshot.class).where("conversationId", eq(conversationId));
    }
}
