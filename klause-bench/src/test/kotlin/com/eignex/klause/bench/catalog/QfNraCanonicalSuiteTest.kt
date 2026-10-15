package com.eignex.klause.bench.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QfNraCanonicalSuiteTest {
    @Test
    fun `canonical set should select its catalog identities without solver availability`() {
        val suite = Catalog.suite("smtlib-qfnra-source-canonical-v1")
        val selected = ProblemSets.load(listOf("qfnra-source-canonical-v1"))

        assertEquals(suite.problems.map { SetEntry(suite.id, it.name) }, selected)
    }

    @Test
    fun `canonical identities should be separate from frozen original selections`() {
        val selected = ProblemSets.load(listOf("qfnra-source-canonical-v1"))
        val originals = ProblemSets.load(listOf("qfnra-core-v1", "qfnra-holdout-v1", "qfnra-extensions-v1"))

        assertTrue(selected.toSet().intersect(originals.toSet()).isEmpty())
    }
}
