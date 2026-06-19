package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import kotlin.math.abs
import kotlin.random.Random
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
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.recompute()
        for (b in 0..1) {
            val move = BoolFlip(b)
            val raw = state.breakScore(move).toDouble()
            val shaped = state.shapedBreakScore(move)
            assertEquals(raw, shaped, "shaped should match raw when shaping is off")
        }
    }

    @Test
    fun `shapedBreakScore incorporates linear objective delta when shaping is on`() {
        // Both flips resolve cost 1 → 0, so they tie on break score; with weights 100 vs 1
        // and lambda 1.0 the shaped scores differ exactly by the weight gap of 99.
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.recompute()
        state.objective = LinearObjective(boolWeights = longArrayOf(100L, 1L))
        state.shapingLambda = 1.0
        val score0 = state.shapedBreakScore(BoolFlip(0))
        val score1 = state.shapedBreakScore(BoolFlip(1))
        assertEquals(99.0, score0 - score1, "shaped break gap must equal objective gap")
    }

    @Test
    fun `shapedObjectiveDelta returns zero when shaping is off`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.recompute()
        assertEquals(0.0, state.shapedObjectiveDelta(BoolFlip(0)))
        state.objective = LinearObjective(boolWeights = longArrayOf(10L, 1L))
        state.shapingLambda = 0.0
        assertEquals(0.0, state.shapedObjectiveDelta(BoolFlip(0)))
    }

    @Test
    fun `shapedObjectiveDelta returns lambda times linear delta when shaping is on`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.recompute()
        state.objective = LinearObjective(boolWeights = longArrayOf(10L, 1L))
        state.shapingLambda = 0.5
        // Flipping bool 0 adds 10 to the objective; lambda 0.5 scales it to 5.
        assertEquals(5.0, state.shapedObjectiveDelta(BoolFlip(0)))
        assertEquals(0.5, state.shapedObjectiveDelta(BoolFlip(1)))
    }

    @Test
    fun `incremental objective drives shaped delta for non-linear objectives`() {
        // |2·b0 - b1 - 1|: a piecewise-linear objective LinearObjective can't express, folded
        // into shaped descent via IncrementalObjective.
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.recompute()
        val abs = object : IncrementalObjective {
            override fun evaluate(sample: Sample): Double {
                val b0 = if (sample.bools[0]) 1 else 0
                val b1 = if (sample.bools[1]) 1 else 0
                return abs(2.0 * b0 - b1 - 1.0)
            }
            override fun deltaIfApplied(assignment: Assignment, move: Move): Double {
                val before = score(assignment.boolValue(0), assignment.boolValue(1))
                val after = when (move) {
                    is Move.BoolFlip -> when (move.varId) {
                        0 -> score(!assignment.boolValue(0), assignment.boolValue(1))
                        1 -> score(assignment.boolValue(0), !assignment.boolValue(1))
                        else -> before
                    }

                    else -> before
                }
                return after - before
            }
            private fun score(b0: Boolean, b1: Boolean): Double {
                val x = if (b0) 1 else 0
                val y = if (b1) 1 else 0
                return abs(2.0 * x - y - 1.0)
            }
        }
        state.objective = abs
        state.shapingLambda = 1.0
        // Current (b0=F, b1=F): |0 - 0 - 1| = 1.
        // Flip b0 → T: |2 - 0 - 1| = 1, delta = 0.
        // Flip b1 → T: |0 - 1 - 1| = 2, delta = +1.
        assertEquals(0.0, state.shapedObjectiveDelta(Move.BoolFlip(0)), 1e-9)
        assertEquals(1.0, state.shapedObjectiveDelta(Move.BoolFlip(1)), 1e-9)
    }

    @Test
    fun `non-incremental non-linear objective falls through to zero shaped delta`() {
        // A plain Objective without IncrementalObjective must yield 0.0 rather than crash or apply-revert.
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.recompute()
        state.objective = object : Objective {
            override fun evaluate(sample: Sample): Double = 42.0
        }
        state.shapingLambda = 1.0
        assertEquals(0.0, state.shapedObjectiveDelta(Move.BoolFlip(0)))
    }

    @Test
    fun `linear shaping minimize on exact one cardinality finds the cheapest pick`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val solver = LocalSearchSolver(problem)
        val sample = solver.minimize(
            objective,
            LocalSearchParams(
                maxFlips = 6_000L,
                randomSeed = 1L,
                costShaping = CostShaping.linear(lambda = 1.0),
            ),
        ).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
    }
}
