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
package ai.badmonkey.agentspaces.springai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * srad/agentspaces-event-loop.md EL-T-13 for this integration (I-06, §10.35): no main class schedules at a fixed rate,
 * so a renewer, flusher or refresher re-arms from its completion and a stall never runs missed rounds back to back.
 * Read from the compiled classes: a class that calls {@code scheduleAtFixedRate} names it in its constant pool.
 */
class NoFixedRateScheduleTest {

    @Test
    void noMainClassSchedulesAtAFixedRate() throws Exception {
        Path classes = Path.of(FleetContext.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        byte[] name = "scheduleAtFixedRate".getBytes(StandardCharsets.UTF_8);
        List<String> offenders;
        try (Stream<Path> files = Files.walk(classes)) {
            offenders = files.filter(p -> p.toString().endsWith(".class")).filter(p -> contains(p, name))
                    .map(p -> classes.relativize(p).toString()).sorted().toList();
        }
        assertThat(offenders).as("main classes calling scheduleAtFixedRate (use scheduleWithFixedDelay or a"
                + " TimeSource one-shot)").isEmpty();
    }

    private static boolean contains(Path file, byte[] needle) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            outer:
            for (int i = 0; i + needle.length <= bytes.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (bytes[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return true;
            }
            return false;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
