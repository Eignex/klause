package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PowerInvariantTest {
    @Test
    fun `aliased predicted and committed moves agree with full grading`() {
        for (output in listOf(0, 1)) for (exponent in listOf(0, 1, 2, 3, 4)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(-10, 100) }, arrayOf<Factor>(Power(0, exponent, output)))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            state.assignment.setInt(0, -2)
            state.assignment.setInt(1, 16)
            state.recompute()
            val before = state.cost
            val predicted = state.netDelta(Move.IntSet(0, 3))

            state.apply(Move.IntSet(0, 3))
            val committed = state.cost
            state.recompute()

            assertEquals(state.cost - before, predicted, "$output $exponent")
            assertEquals(state.cost, committed, "$output $exponent")
        }
    }

    @Test
    fun `maintained power outputs follow signed operand moves`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(-3, 3), IntDomain(-27, 27)), arrayOf<Factor>(Power(0, 3, 1)))
        val sweep = assertNotNull(DefinitionalSweep.infer(problem))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
        state.invariants = sweep.network(2, 0)
        state.assignment.setInt(0, 2)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()

        state.apply(Move.IntSet(0, -3))

        assertEquals(-27L, state.assignment.intValue(1))
        assertEquals(0L, state.cost)
    }
    @Test
    fun `maintained power targets repair every admissible signed root`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(-3, 3), IntDomain(0, 81)), arrayOf<Factor>(Power(0, 4, 1)))
        val sweep = assertNotNull(DefinitionalSweep.infer(problem))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
        state.invariants = sweep.network(2, 0)
        state.assignment.setInt(0, 1)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()

        state.moveSink.addChannelingIntSet(state, 1, 16)

        assertEquals(setOf(-2L, 2L), state.moveSink.list.filterIsInstance<Move.IntSet>().map { it.newValue }.toSet())
        assertEquals(1L, state.assignment.intValue(0))
        for (move in state.moveSink.list.toList()) {
            state.apply(move)
            assertEquals(16L, state.assignment.intValue(1))
            assertEquals(0L, state.cost)
        }
    }

}
