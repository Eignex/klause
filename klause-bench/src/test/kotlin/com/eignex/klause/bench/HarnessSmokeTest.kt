package com.eignex.klause.bench

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HarnessSmokeTest {

    @Test
    fun `verifier finds no disagreement on portfolio`() {
        for (entry in Portfolio.all) {
            val report = Verifier.verify(entry.problem)
            assertNotEquals(Agreement.Disagree, report.agreement,
                "${entry.name}: backends disagree, verdicts=${report.verdicts}")
            assertTrue(report.allSamplesSatisfy,
                "${entry.name}: at least one backend produced an unsatisfying sample, checks=${report.sampleChecks}")
        }
    }
}
