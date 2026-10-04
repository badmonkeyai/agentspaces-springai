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
package ai.badmonkey.agentspaces.springai.worker;

import ai.badmonkey.agentspaces.agent.TakeContext;
import ai.badmonkey.agentspaces.springai.support.FakeTake;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TakeLeaseAdvisorTest {

    record Step(int n) {
    }

    private final SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    /** Calls a local tool three times, then answers. */
    private static ScriptedChatModel threeToolRounds() {
        return new ScriptedChatModel(prompt -> {
            long rounds = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
            if (rounds < 3) {
                return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "c" + rounds, "function", "local_step", "{\"n\":" + rounds + "}"))).build();
            }
            return new AssistantMessage("done after " + rounds);
        });
    }

    @Test
    void everyModelRoundTripInsideTheToolLoopRenewsTheTake() {
        FakeTake take = new FakeTake();
        TakeContext context = take.context("worker");
        TakeLeaseAdvisor advisor = new TakeLeaseAdvisor(scheduler, Duration.ofSeconds(30),
                () -> Optional.of(context));
        ChatClient chat = ChatClient.builder(threeToolRounds()).defaultAdvisors(advisor)
                .defaultToolCallbacks(FunctionToolCallback.builder("local_step", (Step s) -> "ok " + s.n())
                        .description("one step").inputType(Step.class).build())
                .build();

        assertThat(chat.prompt().user("work").call().content()).isEqualTo("done after 3");
        assertThat(take.renewals).as("three tool rounds plus the answer").hasValue(4);
    }

    @Test
    void withNoTakeInFlightTheAdvisorOnlyPassesThrough() {
        TakeLeaseAdvisor advisor = new TakeLeaseAdvisor(scheduler, Duration.ofSeconds(30));
        ChatClient chat = ChatClient.builder(ScriptedChatModel.answering("hi")).defaultAdvisors(advisor).build();
        assertThat(chat.prompt().user("hello").call().content()).isEqualTo("hi");
    }

    @Test
    void theWorkerLoopsThreadBoundTakeIsTheDefault() {
        FakeTake take = new FakeTake();
        TakeLeaseAdvisor advisor = new TakeLeaseAdvisor(scheduler, Duration.ofSeconds(30));
        ChatClient chat = ChatClient.builder(ScriptedChatModel.answering("hi")).defaultAdvisors(advisor).build();
        TakeContext.bind(take.context("worker"));
        try {
            chat.prompt().user("hello").call().content();
        } finally {
            TakeContext.unbind();
        }
        assertThat(take.renewals).hasValue(1);
    }

    @Test
    void aStreamRenewsOnAnIntervalWhileItRuns() {
        FakeTake take = new FakeTake();
        TakeContext context = take.context("worker");
        TakeLeaseAdvisor advisor = new TakeLeaseAdvisor(scheduler, Duration.ofMillis(50),
                () -> Optional.of(context));
        ScriptedChatModel model = ScriptedChatModel.answering("unused")
                .streaming(ScriptedChatModel.chunks(Duration.ofMillis(60),
                        "one ", "two ", "three ", "four ", "five ", "six"));
        ChatClient chat = ChatClient.builder(model).defaultAdvisors(advisor).build();

        String text = String.join("", chat.prompt().user("stream").stream().content().collectList()
                .block(Duration.ofSeconds(10)));
        assertThat(text).isEqualTo("one two three four five six");
        int during = take.renewals.get();
        assertThat(during).as("once at the start, then every 50 ms for ~360 ms").isGreaterThanOrEqualTo(4);
        sleep(200);
        assertThat(take.renewals.get()).as("renewals stop with the stream").isLessThanOrEqualTo(during + 1);
    }

    @Test
    void theAccessorCarriesTheTakeAcrossReactorThreads() {
        TakeContextAccessor accessor = new TakeContextAccessor();
        accessor.afterPropertiesSet();
        reactor.core.publisher.Hooks.enableAutomaticContextPropagation();
        FakeTake take = new FakeTake();
        TakeContext context = take.context("worker");
        TakeContext.bind(context);
        try {
            TakeContext seen = Mono.fromCallable(TakeContext::peek)
                    .subscribeOn(Schedulers.boundedElastic())
                    .contextCapture()
                    .block(Duration.ofSeconds(5));
            assertThat(seen).isSameAs(context);
        } finally {
            TakeContext.unbind();
            reactor.core.publisher.Hooks.disableAutomaticContextPropagation();
            accessor.destroy();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unused")
    private static Message last(List<Message> messages) {
        return messages.get(messages.size() - 1);
    }
}
