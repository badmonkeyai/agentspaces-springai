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
package ai.badmonkey.agentspaces.springai.model.wire;

import java.util.List;

/**
 * One version of a conversation's message window, as chat memory stores it. A
 * save writes the next version and retires the older ones.
 *
 * @param conversationId the conversation
 * @param version        increasing per save
 * @param messages       the window
 */
public record ConversationSnapshot(String conversationId, long version, List<WireMessage> messages) {

    /** Normalizes an absent list. */
    public ConversationSnapshot {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
