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
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Carries the worker's {@link TakeContext} across threads with Micrometer
 * context propagation. Registered with the {@link ContextRegistry}, it lets
 * Reactor's automatic context propagation (Spring Boot's
 * {@code spring.reactor.context-propagation=auto}) move the take onto the
 * threads a streamed model response runs on, so advisors and memory see it
 * there too.
 */
public class TakeContextAccessor implements ThreadLocalAccessor<TakeContext>,
        InitializingBean, DisposableBean {

    /** The context key the take travels under. */
    public static final String KEY = "agentspaces.take-context";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public TakeContext getValue() {
        return TakeContext.peek();
    }

    @Override
    public void setValue(TakeContext value) {
        TakeContext.bind(value);
    }

    @Override
    public void setValue() {
        TakeContext.unbind();
    }

    /** Registers this accessor with the global registry. */
    @Override
    public void afterPropertiesSet() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(this);
    }

    /**
     * Removes this accessor from the global registry, unless another context has
     * since registered its own (several applications can share one JVM).
     */
    @Override
    public void destroy() {
        ContextRegistry registry = ContextRegistry.getInstance();
        if (registry.getThreadLocalAccessors().stream().anyMatch(accessor -> accessor == this)) {
            registry.removeThreadLocalAccessor(KEY);
        }
    }
}
