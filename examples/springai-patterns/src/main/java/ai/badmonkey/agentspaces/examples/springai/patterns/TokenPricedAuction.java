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
package ai.badmonkey.agentspaces.examples.springai.patterns;

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.BidFunction;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;

/**
 * Pattern: model workers priced by tokens. The tasks space runs AUCTION; each
 * worker's {@code @BidFunction} prices a task from its model's price per
 * thousand tokens and an estimate of the prompt's size, and refuses (an
 * infinite bid) tasks harder than its model handles well. The space awards each
 * task to the cheapest capable model, and the winner runs it on its own
 * {@code ChatClient}. Adding a provider means starting one more peer.
 */
public final class TokenPricedAuction {

    /** A task for some model; difficulty runs from 1 (routine) to 10. */
    public record ModelTask(String prompt, int difficulty) {
    }

    /** The winning worker's answer and the price it bid. */
    public record ModelResult(String prompt, String answer, String worker, double price) {
    }

    /** A worker over one model. */
    @AgentSpec(name = "model-worker", description = "Runs tasks on one model, priced by tokens",
            goals = {"answer prompts"})
    public static final class ModelWorker {
        private final String name;
        private final ChatClient chat;
        private final double pricePerThousandTokens;
        private final int maxDifficulty;

        /**
         * Creates a worker.
         *
         * @param name                   the worker's name, stamped on results
         * @param chat                   the worker's model
         * @param pricePerThousandTokens the model's price
         * @param maxDifficulty          the hardest task the model handles well
         */
        public ModelWorker(String name, ChatClient chat, double pricePerThousandTokens, int maxDifficulty) {
            this.name = Objects.requireNonNull(name, "name");
            this.chat = Objects.requireNonNull(chat, "chat");
            this.pricePerThousandTokens = pricePerThousandTokens;
            this.maxDifficulty = maxDifficulty;
        }

        /**
         * Prices a task.
         *
         * @param task the task
         * @return the price, or infinity for a task beyond this model
         */
        @BidFunction(space = "model-tasks")
        public double bid(ModelTask task) {
            if (task.difficulty() > maxDifficulty) {
                return Double.POSITIVE_INFINITY;
            }
            return pricePerThousandTokens * estimatedTokens(task.prompt()) / 1000.0;
        }

        /**
         * Runs a task this worker won.
         *
         * @param task the task
         * @return the result
         */
        @SpaceTake(space = "model-tasks", pollTimeout = "PT0.3S")
        public ModelResult run(ModelTask task) {
            return new ModelResult(task.prompt(), chat.prompt().user(task.prompt()).call().content(), name, bid(task));
        }
    }

    /**
     * A rough token estimate: four characters per token, plus the expected answer.
     *
     * @param prompt the prompt
     * @return the estimate
     */
    public static double estimatedTokens(String prompt) {
        return prompt.length() / 4.0 + 200;
    }

    private TokenPricedAuction() {
    }
}
