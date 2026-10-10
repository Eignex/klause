package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ElementInvariantTest {

    @Test
    fun `affine index repairs reach matching cells through coordinate moves`() {
        for ((rowOffset, colOffset) in listOf(0L to 0L, -3L to 5L, Int.MAX_VALUE.toLong() to 0L)) {
            val factors = arrayOf<Factor>(
                Linear(longArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 2 * rowOffset + colOffset),
                Element(2, 3, longArrayOf(1, 1, 1, 9), arrIsVars = false, indexOffset = 0),
                ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, rowOffset + 1),
            )
            val problem = Problem(
                1, 4,
                arrayOf(
                    IntDomain(rowOffset, rowOffset + 1), IntDomain(colOffset, colOffset + 1),
                    IntDomain(0, 3), IntDomain(9, 9),
                ),
                factors,
            )
            val state = LocalSearchState(problem.bake(), Random(0))
            state.assignment.setInt(0, rowOffset)
            state.assignment.setInt(1, colOffset)
            state.assignment.setInt(2, 0)
            state.assignment.setBool(0, false)
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 4, intArrayOf(2))).network(4, 1)
            state.recompute()

            state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
            state.apply(state.moveSink.list.single())

            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `index repairs skip matching cells that require a pinned or owned coordinate`() {
        for (pinned in listOf(true, false)) {
            val factors = arrayOf<Factor>(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Element(2, 3, longArrayOf(9, 1, 1, 9), arrIsVars = false, indexOffset = 0),
            )
            val problem = Problem(
                0, 4, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3), IntDomain(9, 9)), factors,
            )
            val assumptions = if (pinned) Assumptions(ints = mapOf(0 to 1L)) else Assumptions.None
            val state = LocalSearchState(problem.bake(), Random(0), assumptions)
            state.assignment.setInt(0, 1)
            state.assignment.setInt(1, 0)
            state.assignment.setInt(2, 2)
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 4, intArrayOf(2))).network(4, 0)
            if (!pinned) state.moveSink.setOwners(intArrayOf(7, -1, -1, -1))
            state.recompute()

            state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
            state.apply(state.moveSink.list.single())

            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `an affine index repair cannot select a fractional input value`() {
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Element(1, 2, longArrayOf(1, 1, 1, 9), arrIsVars = false, indexOffset = 0),
        )
        val problem = Problem(0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 3), IntDomain(9, 9)), factors)
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 3, intArrayOf(1))).network(3, 0)
        state.recompute()

        state.factors[1].proposeRepairMoves(state, 1, state.moveSink)

        assertTrue(state.moveSink.list.isEmpty())
    }

    @Test
    fun `an overflowing affine inverse does not produce an input move`() {
        val factors = arrayOf<Factor>(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, Long.MAX_VALUE),
            Element(1, 2, longArrayOf(1, 9), arrIsVars = false, indexOffset = 0),
        )
        val problem = Problem(0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(9, 9)), factors)
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 3, intArrayOf(1))).network(3, 0)
        state.recompute()

        state.factors[1].proposeRepairMoves(state, 1, state.moveSink)

        assertTrue(state.moveSink.list.isEmpty())
    }

    @Test
    fun `violated when index is out of range`() {
        // arr has 3 elements (indices 0..2); idx=-1 is below indexOffset=0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(-1, 5), IntDomain(0, 30)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, -1)
        state.assignment.setInt(1, 10)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `delta predicts improvement when result is set to matching value`() {
        // Violated (result=10 ≠ arr[1]=20); setting result=20 should yield delta < 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 30)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 10)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val delta = state.factors[0].deltaIfIntSet(state, 0, intVar = 1, newValue = 20)
        assertTrue(delta < 0, "setting result=20 should reduce violation; delta=$delta")
    }

}
