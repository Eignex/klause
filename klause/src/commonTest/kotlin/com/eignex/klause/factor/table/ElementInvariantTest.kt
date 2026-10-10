package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElementInvariantTest {

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

    @Test
    fun `matching cell repair preserves large and negative offsets`() {
        for (offset in listOf(Int.MAX_VALUE, Int.MIN_VALUE, -2)) {
            val start = offset.toLong()
            val problem = Problem(
                0,
                2,
                arrayOf(IntDomain(start, start + 1), IntDomain(10, 20)),
                arrayOf<Factor>(Element(0, 1, longArrayOf(10, 20), false, offset)),
            )
            val state = LocalSearchState(problem.bake(), Random(0))
            state.assignment.setInt(0, start)
            state.assignment.setInt(1, 20)
            state.recompute()
            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
            assertTrue(state.moveSink.list.any { it is Move.IntSet && it.varId == 0 && it.newValue == start + 1 })
        }
    }

    @Test
    fun `out of range repair snaps to Long endpoints on both sides`() {
        for (offset in listOf(Int.MAX_VALUE, Int.MIN_VALUE, -2)) {
            val start = offset.toLong()
            for (index in listOf(start - 1, start + 2)) {
                val problem = Problem(
                    0,
                    2,
                    arrayOf(IntDomain(start - 1, start + 2), IntDomain(0, 30)),
                    arrayOf<Factor>(Element(0, 1, longArrayOf(10, 20), false, offset)),
                )
                val state = LocalSearchState(problem.bake(), Random(0))
                state.assignment.setInt(0, index)
                state.assignment.setInt(1, 30)
                state.recompute()
                state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
                val repair = state.moveSink.list.single() as Move.IntSet
                assertEquals(if (index < start) start else start + 1, repair.newValue)
            }
        }
    }

    @Test
    fun `matching cell repairs respect domains and index pins`() {
        val start = Int.MAX_VALUE.toLong()
        for (pins in listOf(Assumptions.None, Assumptions(ints = mapOf(0 to start)))) {
            val domain = if (pins.isEmpty) {
                IntDomain(start, start + 2).excludeValue(start + 1)
            } else {
                IntDomain(start, start + 2)
            }
            val problem = Problem(
                0,
                2,
                arrayOf(domain, IntDomain(10, 20)),
                arrayOf<Factor>(Element(0, 1, longArrayOf(10, 20, 10), false, Int.MAX_VALUE)),
            )
            val state = LocalSearchState(problem.bake(), Random(0), pins)
            state.assignment.setInt(0, start)
            state.assignment.setInt(1, 20)
            state.recompute()
            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
            assertTrue(state.moveSink.list.none { it is Move.IntSet && it.varId == 0 })
        }
    }
}
