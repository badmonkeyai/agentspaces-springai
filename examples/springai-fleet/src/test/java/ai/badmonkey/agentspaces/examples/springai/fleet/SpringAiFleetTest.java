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
package ai.badmonkey.agentspaces.examples.springai.fleet;

import ai.badmonkey.agentspaces.springai.memory.SpaceChatMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Example 15 end to end over TCP: the orchestrator's model calls both workers as fleet tools. */
class SpringAiFleetTest {

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @Timeout(120)
    void theOrchestratorsModelCallsWorkersOnOtherPeersAsTools() throws Exception {
        int seed = freePort();
        try (ConfigurableApplicationContext summarizer = SpringAiFleet.start(SpringAiFleet.SummarizerApp.class, seed, 0);
             ConfigurableApplicationContext translator = SpringAiFleet.start(SpringAiFleet.TranslatorApp.class,
                     freePort(), seed);
             ConfigurableApplicationContext orchestrator = SpringAiFleet.start(SpringAiFleet.OrchestratorApp.class,
                     freePort(), seed)) {
            SpringAiFleet.awaitTools(orchestrator, Duration.ofSeconds(60));
            String answer = orchestrator.getBean(SpringAiFleet.Orchestrator.class)
                    .ask("Brief me on tuple spaces, in French too");
            assertThat(answer).startsWith("Here is your briefing.")
                    .contains("tuple spaces coordinate agents through a shared, leased space.")
                    .contains("[fr] tuple spaces");
            // The workers ran their model calls inside takes, so their memory is keyed by task.
            assertThat(summarizer.getBean(SpaceChatMemoryRepository.class).findConversationIds()).isNotEmpty();
        }
    }
}
