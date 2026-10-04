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

import ai.badmonkey.agentspaces.agent.TakeContext;
import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiProperties.ConversationIdMode;

import java.util.Optional;

/**
 * Chooses the conversation a model call belongs to when the caller names none.
 * The default follows {@code agentspaces.springai.memory.conversation-id}:
 * {@code take} keys by the task's entry ID, so a task that reappears after a
 * crash resumes with the first worker's conversation; {@code agent} keys by the
 * bound agent, one long-running memory per agent; {@code explicit} never
 * chooses. Declare a bean of this type to key by tenant, user, or session.
 */
@FunctionalInterface
public interface ConversationIdResolver {

    /**
     * Resolves the conversation for the current call.
     *
     * @return the conversation ID, or empty to leave the call without memory
     */
    Optional<String> resolve();

    /**
     * The property-driven default.
     *
     * @param mode the configured mode
     * @return the resolver
     */
    static ConversationIdResolver of(ConversationIdMode mode) {
        return switch (mode) {
            case TAKE -> () -> TakeContext.current().map(take -> take.entryId().value());
            case AGENT -> () -> TakeContext.current().map(take -> take.agent().encoded());
            case EXPLICIT -> Optional::empty;
        };
    }
}
