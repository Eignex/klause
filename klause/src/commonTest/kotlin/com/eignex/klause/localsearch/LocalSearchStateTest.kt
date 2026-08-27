package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LocalSearchState.synthesizeChannelingMove] reads the linear structure (coeffs/op/bound) from
 * the original factors, not the parallel invariants which no longer carry it after the
 * propagator/invariant split. These fixtures drive the EQ channeling paths the
 * [strategy.CblsStallSwapTest] fixture (GE/LE only) deliberately avoids.
 */
class LocalSearchStateTest {

    private fun parts(move: Move): List<Move> = (move as Move.Compound).parts

    /** `c == p` reified as `b_p` for p in 0..2, encoded as N parallel single-var EQ reifieds. */
    private fun reifiedChannelingProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 2)),
        factors = arrayOf<Factor>(
            ReifiedLinear(auxBoolVar = 0, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 0),
            ReifiedLinear(auxBoolVar = 1, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 1),
            ReifiedLinear(auxBoolVar = 2, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 2),
        ),
    )

    @Test
    fun `single-var EQ reified channeling rolls indicator flips into one compound`() {
        val state = LocalSearchState(reifiedChannelingProblem(), Random(1))
        state.assignment.setInt(0, 0)
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        state.assignment.setBool(2, false)
        state.recompute()

        val move = state.synthesizeChannelingMove(intVar = 0, newValue = 1)

        val ps = parts(move)
        assertTrue(Move.IntSet(0, 1) in ps, "compound must set the int var to the new value")
        assertTrue(Move.BoolFlip(0) in ps, "indicator for the old value must clear")
        assertTrue(Move.BoolFlip(1) in ps, "indicator for the new value must set")
        assertTrue(Move.BoolFlip(2) !in ps, "untouched indicators must not flip")
        assertEquals(3, ps.size, "no spurious extra parts")
    }

    /** A satisfied `x + y = 3` whose balance is restored by counter-shifting the sibling var. */
    private fun sumChannelingProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
        ),
    )

    @Test
    fun `satisfied Linear EQ channeling counter-shifts a sibling to preserve the sum`() {
        val state = LocalSearchState(sumChannelingProblem(), Random(1))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.recompute()

        val move = state.synthesizeChannelingMove(intVar = 0, newValue = 3)

        val ps = parts(move)
        assertTrue(Move.IntSet(0, 3) in ps, "compound must set the driving var")
        assertTrue(Move.IntSet(1, 0) in ps, "sibling must absorb the +2 drift to keep x + y = 3")
        assertEquals(2, ps.size, "no spurious extra parts")
    }

    @Test
    fun `no sibling indicators leaves a plain int set`() {
        val state = LocalSearchState(sumChannelingProblem(), Random(1))
        // Violated EQ: the caller is repairing it, so no counter-shift is synthesized.
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.recompute()

        val move = state.synthesizeChannelingMove(intVar = 0, newValue = 1)

        assertEquals(Move.IntSet(0, 1), move, "a violated EQ yields a bare int set, not a compound")
    }
}
