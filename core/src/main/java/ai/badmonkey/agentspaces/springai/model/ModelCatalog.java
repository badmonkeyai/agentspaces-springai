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

import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The models a model server serves, and the {@link ChatModel} that serves each.
 * Declare a bean of this type to serve models from a registry or per-tenant
 * configuration; the default serves the names in
 * {@code agentspaces.springai.model-server.models} with the application's
 * provider {@code ChatModel} beans.
 */
public interface ModelCatalog {

    /** The model names this server serves; the first is its default. */
    List<String> models();

    /**
     * The chat model that serves a model name.
     *
     * @param model a model name
     * @return the chat model, or empty when this server does not serve the name
     */
    Optional<ChatModel> model(String model);

    /** The model a request that names none is served with. */
    default String defaultModel() {
        return models().isEmpty() ? "" : models().get(0);
    }

    /**
     * The default catalog: each served name maps to the bean named in
     * {@code modelBeans}, or to the only provider {@code ChatModel} when there is
     * exactly one.
     *
     * @param models     the served names
     * @param providers  the application's provider chat models, by bean name
     * @param modelBeans explicit name-to-bean mappings
     * @return the catalog
     */
    static ModelCatalog of(List<String> models, Map<String, ChatModel> providers,
                           Map<String, String> modelBeans) {
        List<String> served = List.copyOf(models);
        Map<String, ChatModel> byBean = Map.copyOf(providers);
        if (served.isEmpty()) {
            throw new IllegalStateException("agentspaces.springai.model-server.models names no model;"
                    + " list the model names this peer serves");
        }
        for (String model : served) {
            String bean = modelBeans.get(model);
            if (bean != null && !byBean.containsKey(bean)) {
                throw new IllegalStateException("agentspaces.springai.model-server.model-beans maps '"
                        + model + "' to bean '" + bean + "', which is not a ChatModel bean; beans: "
                        + byBean.keySet());
            }
            if (bean == null && byBean.size() != 1) {
                throw new IllegalStateException("model-server serves '" + model + "' but finds "
                        + byBean.size() + " provider ChatModel beans " + byBean.keySet()
                        + "; map it with agentspaces.springai.model-server.model-beans." + model);
            }
        }
        return new ModelCatalog() {
            @Override
            public List<String> models() {
                return served;
            }

            @Override
            public Optional<ChatModel> model(String model) {
                if (!served.contains(model)) {
                    return Optional.empty();
                }
                String bean = modelBeans.get(model);
                return Optional.of(Objects.requireNonNull(bean == null
                        ? byBean.values().iterator().next() : byBean.get(bean)));
            }
        };
    }
}
