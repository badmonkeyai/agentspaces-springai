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

/**
 * Written by a model server when it takes a request: the attempt number the
 * server stamps on its response and chunks. A server that finds an earlier
 * attempt knows a previous server died, and a streaming caller that sees a
 * higher attempt knows its stream restarted.
 *
 * @param requestId the request
 * @param attempt   1 for the first server, higher after each crash
 * @param servedBy  the serving peer
 */
public record ModelAttempt(String requestId, int attempt, String servedBy) {
}
