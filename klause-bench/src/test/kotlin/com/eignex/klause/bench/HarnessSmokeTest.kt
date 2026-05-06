package com.eignex.klause.bench

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HarnessSmokeTest {

    /**
     * Runs the verifier over the full SAT/UNSAT portfolio. If any backend disagrees with
     * the others or returns a sample that doesn't satisfy the problem, this fails loudly.
     * Lightweight CI gate; deeper exploration runs via `./gradlew :klause-bench:run`.
     */
    @Test
    fun verifierFindsNoDisagreementOnPortfolio() {
        for (entry in Portfolio.all) {
            val report = Verifier.verify(entry.problem)
            assertNotEquals(Agreement.Disagree, report.agreement,
                "${entry.name}: backends disagree, verdicts=${report.verdicts}")
            assertTrue(report.allSamplesSatisfy,
                "${entry.name}: at least one backend produced an unsatisfying sample, checks=${report.sampleChecks}")
        }
    }
}
