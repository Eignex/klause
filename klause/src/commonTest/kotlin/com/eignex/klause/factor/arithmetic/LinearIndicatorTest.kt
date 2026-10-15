package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.LeafRealSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearIndicatorTest {
    @Test
    fun `integer indicators should retain both polarities across finite branches`() {
        for (whenOne in listOf(false, true)) {
            val row = ExactLinear.of(
                intArrayOf(Term.ofIntVar(1)),
                listOf(BigFraction.ONE),
                LinearOp.EQ,
                BigFraction.ONE,
            )
            val source = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(LinearIndicator(0, whenOne, row)))

            val points = BacktrackSolver(source.bake()).enumerate().map { it.ints.toList() }.toSet()

            val inactive = if (whenOne) 0L else 1L
            assertEquals(setOf(listOf(inactive, 0L), listOf(inactive, 1L), listOf(1L - inactive, 1L)), points)
        }
    }

    @Test
    fun `indicator score deltas should include a guard aliased with its row operand`() {
        val row = ExactLinear.of(
            intArrayOf(Term.ofIntVar(0)),
            listOf(BigFraction.ONE),
            LinearOp.EQ,
            BigFraction.ZERO,
        )
        val factor = LinearIndicator(0, true, row)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(factor))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3))
        state.assignment.setInt(0, 0)
        state.recompute()
        val invariant = factor.scoringView()
        val before = invariant.violationDegree(state, 0)

        val delta = invariant.deltaIfIntSet(state, 0, 0, 1)
        state.assignment.setInt(0, 1)
        state.recompute()

        assertEquals(invariant.violationDegree(state, 0) - before, delta)
    }

    @Test
    fun `retracting an indicator pin should remove its infeasible numerical row`() {
        val tiny = BigFraction.of(BIG_ONE, parseBigInt("100000000000000000000"))
        for (coefficient in listOf(BigFraction.ONE, tiny)) {
            val row = ExactLinear.of(
                intArrayOf(Term.ofRealVar(0)),
                listOf(coefficient),
                LinearOp.GE,
                BigFraction.ONE,
            )
            val source = Problem(
                0,
                1,
                arrayOf(IntDomain(0, 1)),
                arrayOf(LinearIndicator(0, true, row)),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(0.0),
            )
            LeafRealSolver(source, null).use { completion ->
                val enabled = completion.solve(Sample(booleanArrayOf(), longArrayOf(1)))
                val disabled = completion.solve(Sample(booleanArrayOf(), longArrayOf(0)))

                assertEquals(LpVerdict.INFEASIBLE, enabled.verdict)
                assertEquals(LpVerdict.ATTAINED_OPTIMUM, disabled.verdict)
                assertTrue(enabled.refutingFactors.contains(0))
            }
        }
    }
}
