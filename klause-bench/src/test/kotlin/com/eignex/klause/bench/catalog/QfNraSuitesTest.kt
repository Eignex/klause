package com.eignex.klause.bench.catalog

import kotlin.test.Test
import kotlin.test.assertTrue

class QfNraSuitesTest {
    @Test
    fun `polynomial selections should exclude the explicit extension controls`() {
        val polynomial = ProblemSets.load(listOf("qfnra-core-v1", "qfnra-holdout-v1"))
        val controls = ProblemSets.load(listOf("qfnra-extensions-v1"))

        assertTrue(polynomial.toSet().intersect(controls.toSet()).isEmpty())
        assertTrue(QfNraSuites.all.take(2).flatMap { it.problems }.all { "polynomial-real" in it.tags })
        assertTrue(QfNraSuites.all.last().problems.all { it.expected == Expected.Unknown })
    }
}
