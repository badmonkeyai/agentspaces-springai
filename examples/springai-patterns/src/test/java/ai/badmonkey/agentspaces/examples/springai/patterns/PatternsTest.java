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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The three patterns over TCP, each on its own fleet with scripted models. */
class PatternsTest {

    @Test
    @Timeout(120)
    void aPanelOfModelJudgesApprovesTheTrueClaimAndRejectsTheFalseOne() throws Exception {
        List<JudgePanel.Verdict> verdicts = Patterns.judges(Patterns.freePorts(3));
        assertThat(verdicts.get(0).winner()).isEqualTo("approve");
        assertThat(verdicts.get(0).tally()).contains("approve=3");
        assertThat(verdicts.get(1).winner()).as("the lenient judge is outvoted").isEqualTo("reject");
        assertThat(verdicts.get(1).tally()).contains("reject=2", "approve=1");
    }

    @Test
    @Timeout(120)
    void tokenPricesRouteRoutineWorkToTheCheapModelAndHardWorkToThePremiumOne() throws Exception {
        List<TokenPricedAuction.ModelResult> results = Patterns.auction(Patterns.freePorts(3));
        assertThat(results).extracting(TokenPricedAuction.ModelResult::worker)
                .containsExactly("mini", "mini", "premium", "premium");
        assertThat(results.get(0).answer()).isEqualTo("mini answer");
        assertThat(results.get(3).answer()).isEqualTo("premium answer");
    }

    @Test
    @Timeout(120)
    void theEnsembleFilesTheMoreConfidentReading() throws Exception {
        ExtractionEnsemble.Extraction extraction = Patterns.ensemble(Patterns.freePorts(3));
        assertThat(extraction.value()).isEqualTo("12,400.00");
        assertThat(extraction.extractor()).isEqualTo("careful");
        assertThat(extraction.tally()).contains("careful=2");
    }
}
