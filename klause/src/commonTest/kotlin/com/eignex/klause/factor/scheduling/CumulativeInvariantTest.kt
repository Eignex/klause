package com.eignex.klause.factor.scheduling

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CumulativeInvariantTest {

    private fun threeTasksUnary(): Problem {
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = longArrayOf(2, 2, 2),
            resources = longArrayOf(1, 1, 1),
            capacity = 1,
        )
        return Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `incremental apply matches a recompute`() {
        val problem = threeTasksUnary()
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        val before = state.intPayload[0]
        state.apply(IntSet(1, 2))
        state.apply(IntSet(2, 4))
        val afterIncr = state.intPayload[0]
        val fresh = LocalSearchState(problem.bake(), Random(0))
        fresh.assignment.setInt(0, 0)
        fresh.assignment.setInt(1, 2)
        fresh.assignment.setInt(2, 4)
        fresh.recompute()
        assertEquals(0, afterIncr, "spread schedule should be feasible")
        assertEquals(fresh.intPayload[0], afterIncr, "incremental apply must agree with recompute")
        assertTrue(before > 0, "all-at-zero must start violated")
    }

    @Test
    fun `var resources flip overage as the resource var changes`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(2, 2),
            resources = longArrayOf(1, 1), // ubs
            capacity = 1,
            resourceVars = intArrayOf(2, 3),
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 1), IntDomain(0, 1)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 1)
        state.assignment.setInt(3, 1)
        state.recompute()
        assertTrue(state.cost > 0, "both resources at 1 should overload capacity 1")
        state.assignment.setInt(3, 0)
        state.recompute()
        assertEquals(0, state.cost, "zero resource on one task should remove the overage")
    }

    @Test
    fun `var capacity flips overage as the capacity var changes`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(2, 2),
            resources = longArrayOf(1, 1),
            capacity = 2,
            capacityVar = 2,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(1, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 1)
        state.recompute()
        assertTrue(state.cost > 0, "cap=1 with unit overlap should overage")
        state.assignment.setInt(2, 2)
        state.recompute()
        assertEquals(0, state.cost, "raising cap to 2 should clear overage")
    }

    @Test
    fun `var durations rescale task footprint`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(3, 3), // ubs
            resources = longArrayOf(1, 1),
            capacity = 1,
            durationVars = intArrayOf(2, 3),
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(1, 3), IntDomain(1, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 2)
        state.recompute()
        assertEquals(0, state.cost, "duration 2 each at starts 0 and 2 shouldn't overlap")
        state.assignment.setInt(2, 3)
        state.recompute()
        assertTrue(state.cost > 0, "extending d0 to 3 overlaps task 1 at t=2")
    }
}
