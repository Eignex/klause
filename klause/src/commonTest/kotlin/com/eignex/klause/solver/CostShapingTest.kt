package com.eignex.klause.solver

import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.ViolationPenalty
import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CostShapingTest {

    @Test
    fun `feasibility first treats infeasible as infinite`() {
        val s = CostShaping.FeasibilityFirst
        assertEquals(5.0, s.shape(0, 5.0))
        assertEquals(Double.POSITIVE_INFINITY, s.shape(1, 5.0))
        assertTrue(s.feasibilityGated)
    }

    @Test
    fun `linear shaping mixes violations and objective`() {
        val s = CostShaping.linear(lambda = 0.5)
        assertEquals(0.5 * 4.0, s.shape(0, 4.0))
        assertEquals(2.0 + 0.5 * 4.0, s.shape(2, 4.0))
        assertTrue(!s.feasibilityGated)
    }

    @Test
    fun `saturating shaping caps violation contribution`() {
        val s = CostShaping.saturating(lambda = 1.0, cap = 3.0)
        assertEquals(3.0 + 10.0, s.shape(100, 10.0), "violations should saturate at cap=3")
        assertEquals(1.0 + 10.0, s.shape(1, 10.0), "violation below cap passes through")
    }

    @Test
    fun `sqrt violation penalty grows sub-linearly`() {
        val s = CostShaping.sqrtViolation(lambda = 0.0)
        assertEquals(0.0, s.shape(0, 0.0))
        assertEquals(1.0, s.shape(1, 0.0))
        assertEquals(2.0, s.shape(4, 0.0))
        assertEquals(3.0, s.shape(9, 0.0))
    }

    @Test
    fun `violation penalty types`() {
        assertEquals(7.0, ViolationPenalty.Identity.of(7))
        assertEquals(5.0, ViolationPenalty.Saturating(5.0).of(100))
        assertEquals(3.0, ViolationPenalty.SquareRoot.of(9))
    }

    @Test
    fun `linear shaping minimize on exact one cardinality finds the cheapest pick`() {
        // Same setup as OptimizerTest's exactly-one weighted case, but using shaped descent.
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val solver = LocalSearchSolver(problem)
        val sample = solver.minimize(
            objective,
            LocalSearchParams(
                maxFlips = 50_000L,
                randomSeed = 1L,
                costShaping = CostShaping.linear(lambda = 1.0),
            ),
        )
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
    }
}
