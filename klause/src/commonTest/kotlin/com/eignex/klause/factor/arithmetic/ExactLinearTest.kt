package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExactLinearTest {
    @Test
    fun `remapped repeated coordinates enforce the combined exact relation`() {
        val source = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
            listOf(BigFraction.of(BIG_ONE, bigIntOf(10)), BigFraction.of(BIG_ONE, bigIntOf(5))),
            LinearOp.EQ, BigFraction.of(bigIntOf(3), bigIntOf(5)))
        val remapped = source.remap(VarRemap(intArrayOf(), intArrayOf(0, 0)))
        val model = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf(remapped))

        val result = BacktrackSolver(model.bake()).enumerate().map { it.intValue(0) }.toList()

        assertEquals(listOf(2L), result)
    }

    @Test
    fun `wide exact rows retain holes in finite domains`() {
        val coefficient = BigFraction.of(parseBigInt("100000000000000000001"), BIG_ONE)
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0)), listOf(coefficient), LinearOp.EQ, coefficient)
        val model = Problem(0, 1, arrayOf(IntDomain(0, 2).excludeValue(1)), arrayOf(row))

        val result = BacktrackSolver(model.bake()).solve()

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `exact linear scores agree with finite source truth outside signed word arithmetic`() {
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0)),
            listOf(BigFraction.of(parseBigInt("100000000000000000001"), BIG_ONE)), LinearOp.EQ,
            BigFraction.of(parseBigInt("100000000000000000001"), BIG_ONE))
        val model = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(row))
        val state = LocalSearchState(LocalSearchModel.open(model), Random(3))

        for (value in 0L..2L) {
            state.assignment.setInt(0, value)
            state.recompute()

            assertEquals(value != 1L, state.cost != 0L)
        }
    }

    @Test
    fun `floating exact row score deltas preserve integer and real namespaces`() {
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, BigFraction.ofLong(-2)), LinearOp.EQ, BigFraction.ZERO)
        val model = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val state = LocalSearchState(LocalSearchModel.open(model), Random(3))
        state.assignment.setInt(0, 2)
        state.assignment.setReal(0, 1.0)
        state.recompute()
        val invariant = row.scoringView()
        val before = invariant.violationDegree(state, 0)

        val delta = invariant.deltaIfRealSet(state, 0, 0, 0.5)
        state.assignment.setReal(0, 0.5)
        state.recompute()

        assertEquals(invariant.violationDegree(state, 0) - before, delta)
    }
}
