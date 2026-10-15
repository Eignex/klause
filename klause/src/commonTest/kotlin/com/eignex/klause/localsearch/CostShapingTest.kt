package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Move.BoolFlip
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CostShapingTest {

    @Test
    fun `feasibility first should treat infeasible as infinite`() {
        val s = CostShaping.FeasibilityFirst
        assertEquals(5.0, s.shape(0, 5.0))
        assertEquals(Double.POSITIVE_INFINITY, s.shape(1, 5.0))
        assertTrue(s.feasibilityGated)
    }

    @Test
    fun `saturating shaping should cap the violation contribution`() {
        val s = CostShaping.saturating(lambda = 1.0, cap = 3.0)
        assertEquals(3.0 + 10.0, s.shape(100, 10.0))
        assertEquals(1.0 + 10.0, s.shape(1, 10.0))
    }

    @Test
    fun `shaped break score should include the linear objective delta when shaping is on`() {
        // Both flips resolve cost 1 to 0, so they tie on break score; with weights 100 vs 1
        // and lambda 1.0 the shaped scores differ exactly by the weight gap of 99.
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.recompute()
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(100L, 1L))
        state.shaping.shapingLambda = 1.0
        val score0 = state.shapedBreakScore(BoolFlip(0))
        val score1 = state.shapedBreakScore(BoolFlip(1))
        assertEquals(99.0, score0 - score1)
    }

    @Test
    fun `an incremental objective should drive the shaped delta for non-linear objectives`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.recompute()
        val objective = object : IncrementalObjective {
            override fun evaluate(sample: Sample): Double = score(sample.bools[0], sample.bools[1])
            override fun deltaIfApplied(assignment: Assignment, move: Move): Double {
                val b0 = assignment.boolValue(0)
                val b1 = assignment.boolValue(1)
                val flipped = (move as? Move.BoolFlip)?.varId
                return score(b0 != (flipped == 0), b1 != (flipped == 1)) - score(b0, b1)
            }
            private fun score(b0: Boolean, b1: Boolean): Double =
                abs(2.0 * (if (b0) 1 else 0) - (if (b1) 1 else 0) - 1.0)
        }
        state.shaping.objective = objective
        state.shaping.shapingLambda = 1.0

        assertEquals(0.0, state.shapedObjectiveDelta(Move.BoolFlip(0)), 1e-9)
        assertEquals(1.0, state.shapedObjectiveDelta(Move.BoolFlip(1)), 1e-9)
    }

    @Test
    fun `a non-incremental non-linear objective should yield a zero shaped delta`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        state.recompute()
        state.shaping.objective = object : Objective {
            override fun evaluate(sample: Sample): Double = 42.0
        }
        state.shaping.shapingLambda = 1.0
        assertEquals(0.0, state.shapedObjectiveDelta(Move.BoolFlip(0)))
    }
}
