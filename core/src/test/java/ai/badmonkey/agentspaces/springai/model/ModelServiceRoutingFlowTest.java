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
package ai.badmonkey.agentspaces.springai.model;

import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.springai.support.FleetApps;
import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.springai.support.TestPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F4 routing and trust over TCP. With price routing the requests space runs
 * AUCTION and each server bids its price, so the cheaper server answers every
 * call; and a caller whose authorizer grants MODEL_SERVE to neither server
 * ignores both, and times out.
 */
class ModelServiceRoutingFlowTest {

    private static final String FOUNDING = "springai-model-routing-v1";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CheapServer {
        @Bean
        ScriptedChatModel provider() {
            return ScriptedChatModel.answering("from the cheap server");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class PriceyServer {
        @Bean
        ScriptedChatModel provider() {
            return ScriptedChatModel.answering("from the pricey server");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CallerApp {
    }

    private static Map<String, Object> auction() {
        return Map.of("agentspaces.groups[0].spaces[0].strategy", "AUCTION",
                "agentspaces.groups[0].spaces[0].settle-window-millis", "200");
    }

    private static ConfigurableApplicationContext server(Class<?> app, int seed, double price) {
        Map<String, Object> properties = new HashMap<>(auction());
        properties.putAll(Map.of("agentspaces.springai.model-server.enabled", "true",
                "agentspaces.springai.model-server.models", "test-model",
                "agentspaces.springai.model-server.routing", "price",
                "agentspaces.springai.model-server.prices.test-model", String.valueOf(price)));
        return new SpringApplicationBuilder(app)
                .properties(FleetApps.nodeProperties(FOUNDING, seed, "model-requests"))
                .properties(properties).run();
    }

    @Test
    @Timeout(180)
    void theCheaperServerWinsEveryAuctionAndUngrantedServersAreIgnored() throws Exception {
        int seed = TestPeer.freePort();
        Map<String, Object> client = new HashMap<>(auction());
        client.putAll(Map.of("agentspaces.springai.model-client.enabled", "true",
                "agentspaces.springai.model-client.model", "test-model",
                "agentspaces.springai.model-client.timeout", "30s"));
        try (ConfigurableApplicationContext caller = new SpringApplicationBuilder(CallerApp.class)
                     .properties(FleetApps.nodeProperties(seed, FOUNDING, 0, "model-requests"))
                     .properties(client).run();
             ConfigurableApplicationContext cheap = server(CheapServer.class, seed, 1.0);
             ConfigurableApplicationContext pricey = server(PriceyServer.class, seed, 5.0)) {
            Thread.sleep(2000); // membership settles
            ChatClient chat = caller.getBean(ChatClient.Builder.class).build();
            String cheapPeer = cheap.getBean(PeerIdentity.class).peerId().value();
            for (int i = 0; i < 3; i++) {
                ChatResponse response = chat.prompt().user("price me " + i).call().chatResponse();
                assertThat(response.getResult().getOutput().getText()).isEqualTo("from the cheap server");
                assertThat((String) response.getMetadata().get(FleetChatModel.SERVED_BY_KEY)).isEqualTo(cheapPeer);
            }
            assertThat(pricey.getBean(ScriptedChatModel.class).prompts).isEmpty();

            // A caller that trusts only some other peer to serve models ignores both servers.
            Map<String, Object> suspicious = new HashMap<>(auction());
            suspicious.putAll(Map.of("agentspaces.springai.model-client.enabled", "true",
                    "agentspaces.springai.model-client.model", "test-model",
                    "agentspaces.springai.model-client.timeout", "4s",
                    "agentspaces.security.grants.model-serve[0]", PeerIdentity.generate().peerId().value()));
            try (ConfigurableApplicationContext wary = new SpringApplicationBuilder(CallerApp.class)
                    .properties(FleetApps.nodeProperties(FOUNDING, seed, "model-requests"))
                    .properties(suspicious).run()) {
                Thread.sleep(1500);
                ChatClient waryChat = wary.getBean(ChatClient.Builder.class).build();
                assertThatThrownBy(() -> waryChat.prompt().user("trust me").call().content())
                        .isInstanceOf(FleetModelException.class)
                        .hasMessageContaining("no trusted model server answered");
            }
        }
    }
}
