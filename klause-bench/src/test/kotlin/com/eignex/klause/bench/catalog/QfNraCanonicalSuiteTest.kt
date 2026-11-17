package com.eignex.klause.bench.catalog

import kotlin.test.Test
import kotlin.test.assertTrue

class QfNraCanonicalSuiteTest {
    @Test
    fun `canonical identities should be separate from frozen original selections`() {
        val selected = ProblemSets.load(listOf("qfnra-source-canonical-v1"))
        val originals = ProblemSets.load(listOf("qfnra-core-v1", "qfnra-holdout-v1", "qfnra-extensions-v1"))

        assertTrue(selected.toSet().intersect(originals.toSet()).isEmpty())
    }
}
