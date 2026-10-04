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
 * Token usage of one model call, as a fleet usage entry.
 *
 * @param model            the model
 * @param provider         the provider, as Spring AI's observation names it
 * @param promptTokens     input tokens
 * @param completionTokens output tokens
 * @param totalTokens      all tokens
 * @param agent            the agent that made the call, or empty when unattributed
 * @param peer             the peer that made the call
 */
public record ModelUsage(String model, String provider, long promptTokens, long completionTokens,
                         long totalTokens, String agent, String peer) {
}
