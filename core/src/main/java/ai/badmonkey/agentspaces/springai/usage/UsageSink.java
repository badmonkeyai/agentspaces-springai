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
package ai.badmonkey.agentspaces.springai.usage;

import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;

/**
 * Receives the token usage of every model call this process makes. The default
 * feeds fleet-wide aggregates, optional attributed usage entries, and the
 * console panel; declare a bean of this type to export usage to a billing or
 * FinOps system instead.
 */
@FunctionalInterface
public interface UsageSink {

    /**
     * Records one call's usage.
     *
     * @param usage the usage
     */
    void record(ModelUsage usage);
}
