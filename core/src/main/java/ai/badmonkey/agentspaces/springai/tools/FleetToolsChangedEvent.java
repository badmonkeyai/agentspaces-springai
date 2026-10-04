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

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

/**
 * Published through the application context whenever the fleet's tool set
 * changes: a card arrived, lapsed, or was filtered differently. Listen with
 * {@code @EventListener} to react without polling; the MCP export does.
 *
 * @param added   tool names that appeared
 * @param removed tool names that disappeared
 * @param current every tool in the new set
 */
public record FleetToolsChangedEvent(Set<String> added, Set<String> removed,
                                     List<ToolCallback> current) {

    /** Copies the collections. */
    public FleetToolsChangedEvent {
        added = Set.copyOf(added);
        removed = Set.copyOf(removed);
        current = List.copyOf(current);
    }
}
