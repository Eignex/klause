package com.eignex.klause.bench.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

class NonlinearIntegerSuiteTest {
    @Test
    fun `named set should retain exactly the versioned source identities`() {
        val suite = Catalog.suite("smtlib-nonlinear-integer-v1")

        val selected = ProblemSets.load(listOf("nonlinear-integer-v1"))

        assertEquals(suite.problems.map { SetEntry(suite.id, it.name) }, selected)
    }
}
