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
    fun `shapedBreakScore reduces to breakScore when no shaping configured`() {
        // Default state: no objective injected, lambda 0 — shapedBreakScore must match
        // breakScore exactly for every move.
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(problem, kotlin.random.Random(0))
        state.recompute()
        for (b in 0..1) {
            val move = com.eignex.klause.solver.Move.BoolFlip(b)
            val raw = state.breakScore(move).toDouble()
            val shaped = state.shapedBreakScore(move)
            assertEquals(raw, shaped, "shaped should match raw when shaping is off")
        }
    }

    @Test
    fun `shapedBreakScore incorporates linear objective delta when shaping is on`() {
        // 2-var problem, bool 0 has objective weight 100, bool 1 has weight 1. Both
        // currently false. Flipping bool 0 → true increases objective by 100; flipping
        // bool 1 → true increases objective by 1. With lambda = 1.0, the shaped scores
        // differ exactly by the weight gap.
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(problem, kotlin.random.Random(0))
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.recompute()
        state.objective = LinearObjective(boolWeights = doubleArrayOf(100.0, 1.0))
        state.shapingLambda = 1.0
        val score0 = state.shapedBreakScore(com.eignex.klause.solver.Move.BoolFlip(0))
        val score1 = state.shapedBreakScore(com.eignex.klause.solver.Move.BoolFlip(1))
        // Both flips have the same break score (both resolve cost=1 → 0). Shaped scores
        // differ by 99 (objective coefficient gap).
        assertEquals(99.0, score0 - score1, "shaped break gap must equal objective gap")
    }

    @Test
    fun `shapedObjectiveDelta returns zero when shaping is off`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(problem, kotlin.random.Random(0))
        state.recompute()
        // No objective set → delta is 0 for any move.
        assertEquals(0.0, state.shapedObjectiveDelta(com.eignex.klause.solver.Move.BoolFlip(0)))
        // Objective set but lambda = 0 → still 0.
        state.objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 1.0))
        state.shapingLambda = 0.0
        assertEquals(0.0, state.shapedObjectiveDelta(com.eignex.klause.solver.Move.BoolFlip(0)))
    }

    @Test
    fun `shapedObjectiveDelta returns lambda times linear delta when shaping is on`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(problem, kotlin.random.Random(0))
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.recompute()
        state.objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 1.0))
        state.shapingLambda = 0.5
        // Flipping bool 0 false → true adds 10 to objective; with lambda=0.5, delta = 5.
        assertEquals(5.0, state.shapedObjectiveDelta(com.eignex.klause.solver.Move.BoolFlip(0)))
        assertEquals(0.5, state.shapedObjectiveDelta(com.eignex.klause.solver.Move.BoolFlip(1)))
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
        ).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
    }
}
