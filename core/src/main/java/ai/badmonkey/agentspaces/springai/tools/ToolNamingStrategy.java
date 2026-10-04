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

/**
 * Names the tool a fleet action becomes. The provider makes names unique, so
 * a strategy only needs to produce a valid base name: letters, digits, '_' and
 * '-', at most 64 characters (the common limit across model providers).
 * Declare a bean of this type to replace the default.
 */
@FunctionalInterface
public interface ToolNamingStrategy {

    /** The longest tool name model providers commonly accept. */
    int MAX_LENGTH = 64;

    /**
     * Names a fleet action's tool.
     *
     * @param action the action
     * @return the base tool name
     */
    String toolName(FleetAction action);

    /**
     * The default: a prefix and the action's name, sanitized and truncated.
     *
     * @param prefix the prefix, for example {@code fleet_}
     * @return the strategy
     */
    static ToolNamingStrategy prefixed(String prefix) {
        return action -> sanitize(prefix + action.name());
    }

    /**
     * Replaces characters providers refuse and truncates to {@link #MAX_LENGTH}.
     *
     * @param name a candidate name
     * @return a valid tool name
     */
    static String sanitize(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9_-]", "_");
        if (clean.isEmpty()) {
            clean = "fleet_tool";
        }
        return clean.length() <= MAX_LENGTH ? clean : clean.substring(0, MAX_LENGTH);
    }
}
