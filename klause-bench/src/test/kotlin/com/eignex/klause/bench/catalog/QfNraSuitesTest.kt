package com.eignex.klause.bench.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QfNraSuitesTest {
    @Test
    fun `frozen sets should select all declared identities without solver availability`() {
        for (tier in listOf("core", "holdout", "extensions")) {
            val suite = Catalog.suite("smtlib-qfnra-$tier-v1")
            val selected = ProblemSets.load(listOf("qfnra-$tier-v1"))

            assertEquals(suite.problems.map { SetEntry(suite.id, it.name) }, selected)
            assertTrue(suite.problems.all { it.format == Format.SMTLIB })
        }
    }

    @Test
    fun `polynomial selections should exclude the explicit extension controls`() {
        val polynomial = ProblemSets.load(listOf("qfnra-core-v1", "qfnra-holdout-v1"))
        val controls = ProblemSets.load(listOf("qfnra-extensions-v1"))

        assertTrue(polynomial.toSet().intersect(controls.toSet()).isEmpty())
        assertTrue(QfNraSuites.all.take(2).flatMap { it.problems }.all { "polynomial-real" in it.tags })
        assertTrue(QfNraSuites.all.last().problems.all { it.expected == Expected.Unknown })
    }
}
