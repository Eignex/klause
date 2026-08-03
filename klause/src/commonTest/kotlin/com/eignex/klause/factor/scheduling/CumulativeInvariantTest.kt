package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `non-overlapping schedule satisfies the cumulative bound`() {
        val problem = threeTasksUnary()
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 4)
        state.recompute()
        assertEquals(0, state.cost, "back-to-back schedule should satisfy capacity 1")
    }

    @Test
    fun `overlapping tasks blow the unary capacity`() {
        val problem = threeTasksUnary()
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 4)
        state.recompute()
        assertTrue(state.cost > 0, "two overlapping unit tasks should violate capacity 1")
    }

    @Test
    fun `graded cost equals the summed overage`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(3, 3),
            resources = longArrayOf(2, 2),
            capacity = 3,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.recompute()
        assertEquals(2, state.intPayload[0], "expected energy overage of 2 (one unit at t=1,t=2)")
    }

    @Test
    fun `incremental apply matches a recompute`() {
        val problem = threeTasksUnary()
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        val before = state.intPayload[0]
        state.apply(IntSet(1, 2))
        state.apply(IntSet(2, 4))
        val afterIncr = state.intPayload[0]
        val fresh = LocalSearchState(problem, Random(0))
        fresh.assignment.setInt(0, 0)
        fresh.assignment.setInt(1, 2)
        fresh.assignment.setInt(2, 4)
        fresh.recompute()
        assertEquals(0, afterIncr, "spread schedule should be feasible")
        assertEquals(fresh.intPayload[0], afterIncr, "incremental apply must agree with recompute")
        assertTrue(before > 0, "all-at-zero must start violated")
    }

    @Test
    fun `LS finds a feasible schedule for the 3-task unary problem`() {
        val problem = threeTasksUnary()
        val solver = LocalSearchSolver(
            problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 11L)).assignment
        assertNotNull(sample, "LS should find a feasible cumulative schedule")
        val starts = sample.ints
        val occ = IntArray(8)
        for (i in 0 until 3) {
            for (t in starts[i] until starts[i] + 2) {
                if (t in occ.indices) occ[t.toInt()]++
            }
        }
        for (t in occ.indices) assertTrue(occ[t] <= 1, "unary capacity broken at t=$t in ${starts.toList()}")
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
        val state = LocalSearchState(problem, Random(0))
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
        val state = LocalSearchState(problem, Random(0))
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
        val state = LocalSearchState(problem, Random(0))
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
