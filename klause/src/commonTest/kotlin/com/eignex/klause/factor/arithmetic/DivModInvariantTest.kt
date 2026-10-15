package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.util.bigIntOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DivModInvariantTest {
    @Test
    fun `aliased moves predict the complete relation grade`() {
        for (roles in listOf(intArrayOf(0, 1, 2, 3), intArrayOf(0, 0, 1, 2), intArrayOf(0, 1, 0, 2),
            intArrayOf(0, 1, 2, 2), intArrayOf(0, 1, 2, 0))) {
            val problem = Problem(0, 4, Array(4) { IntDomain(-8, 8) },
                arrayOf<Factor>(DivMod(roles[0], roles[1], roles[2], roles[3])))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            for (v in 0..3) state.assignment.setInt(v, (v + 1).toLong())
            state.recompute()
            val before = state.cost
            val predicted = state.netDelta(Move.IntSet(0, -7))

            state.apply(Move.IntSet(0, -7))
            val committed = state.cost
            state.recompute()

            assertEquals(state.cost - before, predicted, roles.contentToString())
            assertEquals(state.cost, committed, roles.contentToString())
        }
    }

    @Test
    fun `maintained quotient and remainder follow dividend moves together`() {
        val source = Problem(0, 3, arrayOf(IntDomain(-8, 8), IntDomain(-3, 3), IntDomain(-2, 2)),
            arrayOf<Factor>(DivMod(0, bigIntOf(3), 1, 2)))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        val sweep = assertNotNull(DefinitionalSweep.infer(source))
        state.invariants = sweep.network(source.numIntVars, source.numBoolVars)
        state.assignment.setInt(0, 7)
        sweep.sweep(state.assignment, state.rootDomains, source.factors)
        state.recompute()

        state.apply(Move.IntSet(0, -7))

        assertEquals(-2L, state.assignment.intValue(1))
        assertEquals(-1L, state.assignment.intValue(2))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `direct repairs offer a correlated output update`() {
        val source = Problem(0, 3, arrayOf(IntDomain(-8, 8), IntDomain(-3, 3), IntDomain(-2, 2)),
            arrayOf<Factor>(DivMod(0, bigIntOf(3), 1, 2)))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.assignment.setInt(0, -7)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()

        state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

        assertTrue(state.moveSink.list.any { move -> state.netDelta(move) == -state.cost })
    }
}
