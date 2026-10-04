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

import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.spring.SpaceAgent;
import ai.badmonkey.agentspaces.springai.tools.FleetTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Example 15: a Spring AI application on the fleet. Two worker applications,
 * a summarizer and a translator, are ordinary Spring AI code: a
 * {@code @SpaceAgent} bean with an injected {@code ChatClient.Builder} and one
 * {@code @SpaceTake} method. The orchestrator application attaches the fleet's
 * tools to its {@code ChatClient}, and its model calls both workers, on other
 * peers, from its own tool loop. No application knows another's address or
 * name; the workers' AgentCards are the tool catalog.
 *
 * <p>Run all three in one process with {@code mvn -q -pl examples/springai-fleet exec:java}.
 * The demo model is scripted so the example runs offline; set
 * {@code example.demo-model=false} and add a Spring AI model starter to use a
 * real provider.
 */
public final class SpringAiFleet {

    /** A briefing request the summarizer takes. */
    public record Brief(String topic) {
    }

    /** The summarizer's answer. */
    public record Summary(String topic, String text) {
    }

    /** A translation request the translator takes. */
    public record TranslateTask(String text, String language) {
    }

    /** The translator's answer. */
    public record Translation(String language, String translated) {
    }

    /** The summarizer: a Spring AI worker on the fleet. */
    @SpaceAgent(name = "summarizer", description = "Summarizes a topic into a short briefing",
            goals = {"summarize topics"})
    public static class Summarizer {
        private final ChatClient chat;

        Summarizer(ChatClient.Builder builder) {
            this.chat = builder.build();
        }

        /**
         * Summarizes a topic with the model.
         *
         * @param brief the request
         * @return the summary
         */
        @SpaceTake(space = "work", lease = "2m")
        public Summary summarize(Brief brief) {
            return new Summary(brief.topic(), chat.prompt().user("Summarize: " + brief.topic()).call().content());
        }
    }

    /** The translator: another Spring AI worker. */
    @SpaceAgent(name = "translator", description = "Translates text into another language",
            goals = {"translate text"})
    public static class Translator {
        private final ChatClient chat;

        Translator(ChatClient.Builder builder) {
            this.chat = builder.build();
        }

        /**
         * Translates text with the model.
         *
         * @param task the request
         * @return the translation
         */
        @SpaceTake(space = "work", lease = "2m")
        public Translation translate(TranslateTask task) {
            return new Translation(task.language(), chat.prompt()
                    .user("Translate to " + task.language() + ": " + task.text()).call().content());
        }
    }

    /** The orchestrator: a ChatClient whose tools are the fleet's agents. */
    public static class Orchestrator {
        private final ChatClient chat;

        Orchestrator(ChatClient.Builder builder, FleetTools fleet) {
            this.chat = builder.defaultToolCallbacks(fleet.toolCallbacks()).build();
        }

        /**
         * Answers a question, calling fleet agents as the model sees fit.
         *
         * @param question the question
         * @return the answer
         */
        public String ask(String question) {
            return chat.prompt().user(question).call().content();
        }
    }

    /** The scripted demo model, on unless {@code example.demo-model=false}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "example.demo-model", havingValue = "true", matchIfMissing = true)
    static class DemoModel {
        @Bean
        ChatModel demoChatModel() {
            return new DemoChatModel();
        }
    }

    /** The summarizer application. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({DemoModel.class, Summarizer.class})
    public static class SummarizerApp {
    }

    /** The translator application. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({DemoModel.class, Translator.class})
    public static class TranslatorApp {
    }

    /** The orchestrator application. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(DemoModel.class)
    public static class OrchestratorApp {
        @Bean
        Orchestrator orchestrator(ChatClient.Builder builder, FleetTools fleet) {
            return new Orchestrator(builder, fleet);
        }
    }

    private SpringAiFleet() {
    }

    /**
     * The properties of one node of the example's fleet.
     *
     * @param port     the port to listen on
     * @param seedPort a member's port, or 0 for the first node
     * @return the properties
     */
    public static Map<String, Object> node(int port, int seedPort) {
        Map<String, Object> properties = new java.util.HashMap<>(Map.of(
                "spring.main.web-application-type", "none",
                "spring.main.banner-mode", "off",
                "agentspaces.security.profile", "dev-local",
                "agentspaces.bind", "127.0.0.1:" + port,
                "agentspaces.groups[0].name", "springai-fleet",
                "agentspaces.groups[0].founding", "springai-fleet-example-v1",
                "agentspaces.groups[0].spaces[0].name", "work",
                "agentspaces.groups[0].spaces[1].name", "conversations",
                "agentspaces.springai.memory.enabled", "true"));
        if (seedPort > 0) {
            properties.put("agentspaces.groups[0].seeds[0]", "127.0.0.1:" + seedPort);
        }
        return properties;
    }

    /**
     * Starts one application of the fleet.
     *
     * @param app      the application class
     * @param port     its port
     * @param seedPort a member's port, or 0
     * @return the running context
     */
    public static ConfigurableApplicationContext start(Class<?> app, int port, int seedPort) {
        return new SpringApplicationBuilder(app).properties(node(port, seedPort)).run();
    }

    /**
     * Waits until the orchestrator's model can see both workers as tools.
     *
     * @param orchestrator the orchestrator context
     * @param timeout      how long to wait
     */
    public static void awaitTools(ConfigurableApplicationContext orchestrator, Duration timeout)
            throws InterruptedException {
        FleetTools tools = orchestrator.getBean(FleetTools.class);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<String> names = tools.current().stream().map(ToolCallback::getToolDefinition)
                    .map(d -> d.name()).toList();
            if (names.contains("fleet_summarizer_Brief") && names.contains("fleet_translator_TranslateTask")) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("the workers' cards did not arrive within " + timeout);
    }

    /**
     * Runs the demo: three applications, one question.
     *
     * @param args unused
     */
    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext summarizer = start(SummarizerApp.class, 7901, 0);
             ConfigurableApplicationContext translator = start(TranslatorApp.class, 7902, 7901);
             ConfigurableApplicationContext orchestrator = start(OrchestratorApp.class, 7903, 7901)) {
            awaitTools(orchestrator, Duration.ofSeconds(30));
            System.out.println("\nfleet tools: " + orchestrator.getBean(FleetTools.class).current().stream()
                    .map(t -> t.getToolDefinition().name()).toList());
            String answer = orchestrator.getBean(Orchestrator.class).ask("Brief me on tuple spaces, in French too");
            System.out.println("answer: " + answer + "\n");
        }
    }
}
