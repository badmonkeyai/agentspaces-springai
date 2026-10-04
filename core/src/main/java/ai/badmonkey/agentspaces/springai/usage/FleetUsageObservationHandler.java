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

import ai.badmonkey.agentspaces.agent.TakeContext;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

import java.util.Objects;

/**
 * Turns Spring AI's {@code gen_ai.client.operation} observations into fleet
 * usage. Spring Boot registers every {@code ObservationHandler} bean with the
 * {@code ObservationRegistry}, so this sees every provider model call in the
 * process, including calls that bypass {@code ChatClient}, and attributes each
 * to the agent whose take is in flight. Calls made through {@code FleetChatModel}
 * are counted where their provider runs, on the model server, so fleet totals
 * never count a call twice.
 */
public class FleetUsageObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    private final UsageSink sink;
    private final String peer;

    /**
     * Creates the handler.
     *
     * @param sink where usage goes
     * @param peer this peer, for attribution
     */
    public FleetUsageObservationHandler(UsageSink sink, String peer) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.peer = Objects.requireNonNull(peer, "peer");
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        ChatResponse response = context.getResponse();
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        String model = response.getMetadata().getModel();
        if (model == null || model.isBlank()) {
            model = context.getRequest() != null && context.getRequest().getOptions() != null
                    && context.getRequest().getOptions().getModel() != null
                    ? context.getRequest().getOptions().getModel() : "unknown";
        }
        String provider = context.getOperationMetadata() == null ? "" : context.getOperationMetadata().provider();
        String agent = TakeContext.current().map(take -> take.agent().encoded()).orElse("");
        sink.record(new ModelUsage(model, provider == null ? "" : provider, value(usage.getPromptTokens()),
                value(usage.getCompletionTokens()), value(usage.getTotalTokens()), agent, peer));
    }

    private static long value(Integer tokens) {
        return tokens == null ? 0 : tokens;
    }
}
