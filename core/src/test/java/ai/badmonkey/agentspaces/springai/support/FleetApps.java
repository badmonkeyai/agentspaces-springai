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
package ai.badmonkey.agentspaces.springai.support;

import ai.badmonkey.agentspaces.spring.AgentSpacesAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.AgentSpacesSpringAiAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.FleetDiscoveryToolsAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.FleetEmbeddingAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.FleetMcpAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.FleetMemoryAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.FleetModelClientAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.FleetModelServerAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.FleetWorkerAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.FleetToolsAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.FleetUsageAutoConfiguration;
import ai.badmonkey.agentspaces.springai.autoconfigure.FleetVectorStoreAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

/** Shared Spring wiring for the tests: the starter's properties for a one-group dev-local fleet. */
public final class FleetApps {

    private FleetApps() {
    }

    /**
     * The properties of a node in the test group, listening on a free port.
     *
     * @param founding the group's founding string
     * @param seedPort a member's port, or 0
     * @param spaces   the spaces the group configures
     * @return {@code key=value} pairs
     */
    public static String[] nodeProperties(String founding, int seedPort, String... spaces) {
        return nodeProperties(TestPeer.freePort(), founding, seedPort, spaces);
    }

    /** As {@link #nodeProperties(String, int, String...)}, listening on {@code bindPort}. */
    public static String[] nodeProperties(int bindPort, String founding, int seedPort, String... spaces) {
        List<String> properties = new ArrayList<>(List.of(
                "spring.main.web-application-type=none",
                "spring.ai.mcp.server.enabled=false",
                "agentspaces.security.profile=dev-local",
                "agentspaces.bind=127.0.0.1:" + bindPort,
                "agentspaces.tick-millis=200",
                "agentspaces.groups[0].name=test-fleet",
                "agentspaces.groups[0].founding=" + founding));
        if (seedPort > 0) {
            properties.add("agentspaces.groups[0].seeds[0]=127.0.0.1:" + seedPort);
        }
        for (int i = 0; i < spaces.length; i++) {
            properties.add("agentspaces.groups[0].spaces[" + i + "].name=" + spaces[i]);
            properties.add("agentspaces.groups[0].spaces[" + i + "].settle-window-millis=100");
        }
        return properties.toArray(String[]::new);
    }

    /** A context runner with the starter and every agentspaces-springai auto-configuration. */
    public static ApplicationContextRunner runner(String... spaces) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentSpacesAutoConfiguration.class,
                        AgentSpacesSpringAiAutoConfiguration.class, FleetToolsAutoConfiguration.class,
                        FleetDiscoveryToolsAutoConfiguration.class, FleetWorkerAutoConfiguration.class,
                        FleetMemoryAutoConfiguration.class, FleetModelClientAutoConfiguration.class,
                        FleetModelServerAutoConfiguration.class, FleetEmbeddingAutoConfiguration.class,
                        FleetUsageAutoConfiguration.class, FleetMcpAutoConfiguration.class,
                        FleetVectorStoreAutoConfiguration.class))
                .withPropertyValues(nodeProperties("runner-" + System.nanoTime(), 0,
                        spaces.length == 0 ? new String[] {"work"} : spaces));
    }
}
