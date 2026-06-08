package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.runner.InProcessRunner
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VerifyTest {

    @Test
    fun `backends agree on the handwritten core`() {
        for (ref in Catalog.suite("handwritten-core").problems) {
            val problem = InProcessRunner.resolve(ref).problem
            val report = Verifier.verify(problem)
            assertNotEquals(
                Agreement.Disagree,
                report.agreement,
                "${ref.name}: backends disagree, verdicts=${report.verdicts}",
            )
            assertTrue(
                report.allSamplesSatisfy,
                "${ref.name}: a backend produced an unsatisfying sample, checks=${report.sampleChecks}",
            )
        }
    }
}
