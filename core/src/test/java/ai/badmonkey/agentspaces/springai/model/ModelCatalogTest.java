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

import ai.badmonkey.agentspaces.springai.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCatalogTest {

    private final ChatModel openai = ScriptedChatModel.answering("openai");
    private final ChatModel ollama = ScriptedChatModel.answering("ollama");

    @Test
    void withOneProviderEveryServedNameMapsToIt() {
        ModelCatalog catalog = ModelCatalog.of(List.of("gpt-4o", "gpt-4o-mini"), Map.of("openAiChatModel", openai),
                Map.of());

        assertThat(catalog.models()).containsExactly("gpt-4o", "gpt-4o-mini");
        assertThat(catalog.defaultModel()).as("the first name is the default").isEqualTo("gpt-4o");
        assertThat(catalog.model("gpt-4o")).containsSame(openai);
        assertThat(catalog.model("gpt-4o-mini")).containsSame(openai);
    }

    @Test
    void aNameItDoesNotServeIsEmpty() {
        ModelCatalog catalog = ModelCatalog.of(List.of("gpt-4o"), Map.of("openAiChatModel", openai), Map.of());

        assertThat(catalog.model("llama3")).isEmpty();
    }

    @Test
    void modelBeansRouteEachNameToItsProvider() {
        ModelCatalog catalog = ModelCatalog.of(List.of("gpt-4o", "llama3"),
                Map.of("openAiChatModel", openai, "ollamaChatModel", ollama),
                Map.of("gpt-4o", "openAiChatModel", "llama3", "ollamaChatModel"));

        assertThat(catalog.model("gpt-4o")).containsSame(openai);
        assertThat(catalog.model("llama3")).containsSame(ollama);
    }

    @Test
    void anUnmappedNameBesideSeveralProvidersIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> ModelCatalog.of(List.of("gpt-4o", "llama3"),
                Map.of("openAiChatModel", openai, "ollamaChatModel", ollama),
                Map.of("gpt-4o", "openAiChatModel")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'llama3'")
                .hasMessageContaining("agentspaces.springai.model-server.model-beans.llama3");
    }

    @Test
    void aNameMappedToAnUnknownBeanIsRefusedListingTheBeans() {
        assertThatThrownBy(() -> ModelCatalog.of(List.of("gpt-4o"), Map.of("openAiChatModel", openai),
                Map.of("gpt-4o", "missingBean")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'missingBean'")
                .hasMessageContaining("openAiChatModel");
    }

    @Test
    void noProviderAtAllIsRefused() {
        assertThatThrownBy(() -> ModelCatalog.of(List.of("gpt-4o"), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finds 0 provider ChatModel beans");
    }

    @Test
    void servingNoModelIsRefused() {
        assertThatThrownBy(() -> ModelCatalog.of(List.of(), Map.of("openAiChatModel", openai), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentspaces.springai.model-server.models names no model");
    }

    @Test
    void theCatalogIsAFixedCopyOfItsInputs() {
        List<String> names = new java.util.ArrayList<>(List.of("gpt-4o"));
        Map<String, ChatModel> providers = new java.util.HashMap<>(Map.of("openAiChatModel", openai));
        ModelCatalog catalog = ModelCatalog.of(names, providers, Map.of());
        names.add("llama3");
        providers.put("ollamaChatModel", ollama);

        assertThat(catalog.models()).containsExactly("gpt-4o");
        assertThat(catalog.model("gpt-4o")).containsSame(openai);
    }

    @Test
    void anEmptyCatalogHasNoDefault() {
        ModelCatalog empty = new ModelCatalog() {
            @Override
            public List<String> models() {
                return List.of();
            }

            @Override
            public java.util.Optional<ChatModel> model(String model) {
                return java.util.Optional.empty();
            }
        };
        assertThat(empty.defaultModel()).isEmpty();
    }
}
