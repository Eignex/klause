package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacktrackSamplesDiversityTest {

    @Test
    fun `samples yields diverse first-SAT leaves across restarts`() {
        // exactly-one over 8 bools — 8 feasible models. DFS-without-restarts would yield
        // them in trail order; samples should give us a varied subset.
        val factor = Cardinality.exactlyOne((0..7).map { Lit.make(it, true) }.toIntArray())
        val problem = Problem(8, 0, emptyArray(), listOf(factor))
        val results = BacktrackSolver(problem.bake())
            .samples(BacktrackParams(randomSeed = 42L))
            .take(20).toList()
        assertEquals(20, results.size)
        // With random restarts we expect several different "which var is true" outcomes.
        val distinctTrueIndices = results.map { it.bools.indexOfFirst { b -> b } }.toSet()
        assertTrue(
            distinctTrueIndices.size >= 3,
            "expected diverse samples; got only ${distinctTrueIndices.size} distinct true-vars: $distinctTrueIndices",
        )
    }

    @Test
    fun `enumerate still distinct and complete on small problem`() {
        // 4-var cardinality — exactly 4 feasible models. Enumerate must produce all 4 distinct.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val models = BacktrackSolver(problem.bake())
            .enumerate(BacktrackParams(minHammingDistance = 0))
            .toList()
        assertEquals(4, models.size)
        assertEquals(4, models.toSet().size)
    }
}
