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

import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.space.local.LocalSpace;
import ai.badmonkey.agentspaces.springai.model.wire.ConversationSnapshot;
import ai.badmonkey.agentspaces.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpaceChatMemoryRepositoryTest {

    private final TestClock clock = TestClock.create();
    private final LocalSpace space = LocalSpace.builder("conversations", PeerIdentity.generate().agent("host"))
            .clock(clock).build();
    private final SpaceChatMemoryRepository repository = new SpaceChatMemoryRepository(space, Duration.ofHours(1));

    @AfterEach
    void tearDown() {
        space.close();
    }

    @Test
    void aSavedConversationReadsBackMessageForMessage() {
        List<Message> messages = List.of(new SystemMessage("be brief"), new UserMessage("plan the offsite"),
                AssistantMessage.builder().content("three steps")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "calendar", "{}")))
                        .build());
        repository.saveAll("task-1", messages);

        List<Message> read = repository.findByConversationId("task-1");
        assertThat(read).extracting(Message::getText).containsExactly("be brief", "plan the offsite", "three steps");
        assertThat(((AssistantMessage) read.get(2)).getToolCalls()).extracting(AssistantMessage.ToolCall::name)
                .containsExactly("calendar");
        assertThat(repository.findConversationIds()).containsExactly("task-1");
    }

    @Test
    void aSaveReplacesTheWindowAndRetiresOlderVersions() {
        repository.saveAll("task-1", List.of(new UserMessage("one")));
        repository.saveAll("task-1", List.of(new UserMessage("one"), new AssistantMessage("two")));
        repository.saveAll("task-1", List.of(new AssistantMessage("two"), new UserMessage("three")));

        assertThat(repository.findByConversationId("task-1")).extracting(Message::getText)
                .containsExactly("two", "three");
        assertThat(space.readAll(Template.of(ConversationSnapshot.class), 10))
                .as("one live snapshot per conversation")
                .singleElement().satisfies(s -> assertThat(s.version()).isEqualTo(3));
    }

    @Test
    void deleteRemovesTheConversationAndLeavesOthers() {
        repository.saveAll("a", List.of(new UserMessage("a")));
        repository.saveAll("b", List.of(new UserMessage("b")));
        repository.deleteByConversationId("a");
        assertThat(repository.findByConversationId("a")).isEmpty();
        assertThat(repository.findConversationIds()).containsExactly("b");
    }

    @Test
    void anAbandonedConversationLapsesWithItsLease() {
        repository.saveAll("task-1", List.of(new UserMessage("remember me")));
        clock.advance(Duration.ofHours(2));
        space.sweepNow();
        assertThat(repository.findByConversationId("task-1")).isEmpty();
    }
}
