package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DisjunctiveTest {

    /** Three unit-duration tasks on a single machine, start domain [0, 2]. */
    private fun threeUnitTasks(): Problem {
        val factor = Disjunctive(starts = intArrayOf(0, 1, 2), durations = intArrayOf(1, 1, 1))
        return Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(factor),
        )
    }

    @Test
    fun `non-overlapping schedule satisfies disjunctive`() {
        val problem = threeUnitTasks()
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.recompute()
        assertEquals(0, state.cost)
    }

    @Test
    fun `overlap counts as violation`() {
        val problem = threeUnitTasks()
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 2)
        state.recompute()
        assertTrue(state.cost > 0, "tasks at the same time should violate disjunctive")
    }

    @Test
    fun `pairwise detectable precedence pushes the earliest start`() {
        // Task 0 duration 3 dom [0, 0] — fully fixed at time 0, mandatory part [0, 3).
        // Task 1 duration 1 dom [0, 5]. Time-tabling alone (mandatory part) pushes
        // start_1.min to 3, so this trivially holds.
        val factor = Disjunctive(starts = intArrayOf(0, 1), durations = intArrayOf(3, 1))
        val problem = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 5)),
            factors = listOf(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        // dom[1] becomes [3, 5] — not pinned. Verify by checking that pinning t=2 fails.
        val unsatPin = problem.propagate(Assumptions(ints = mapOf(1 to 2)))
        assertTrue(unsatPin is PropagationResult.Unsat, "pinning task 1 at t=2 must fail; got $unsatPin")
        // And pinning at t=3 should succeed.
        val okPin = problem.propagate(Assumptions(ints = mapOf(1 to 3)))
        assertTrue(okPin is PropagationResult.Implied, "pinning task 1 at t=3 should succeed; got $okPin")
    }

    @Test
    fun `edge-finding pins a task forced to come last by an energetic overflow`() {
        // Three duration-2 tasks. Tasks 0 and 1 have dom [0, 3], task 2 has dom [0, 4].
        // Total demand = 6 time units; the tight cluster {0, 1} alone fits in [0, 5]
        // (est=0, lct=5, sum_dur=4 — slack 1). Adding task 2 (dur 2) into the union
        // makes est + dur_2 + sum_dur({0,1}) = 0 + 2 + 4 = 6 > lct({0,1}) = 5 — edge-
        // finding fires and pushes start_2.min ≥ est({0,1}) + sum_dur({0,1}) = 4. Since
        // dom_2 = [0, 4], task 2 collapses to the singleton {4} → Implied.ints[2] = 4.
        // Pairwise detectable precedences alone cannot derive this because no single
        // pair triggers (est_i + dur_i ≤ lst_j for every i, j pair in [0, 3] dom).
        val factor = Disjunctive(starts = intArrayOf(0, 1, 2), durations = intArrayOf(2, 2, 2))
        val problem = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 4)),
            factors = listOf(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        assertEquals(4, result.ints[2], "edge-finding should pin task 2's start to 4; implied=${result.ints}")
    }

    @Test
    fun `BacktrackSolver enumerates exactly the 6 disjunctive schedules of three unit tasks`() {
        val problem = threeUnitTasks()
        val solver = BacktrackSolver(problem)
        val samples = solver.enumerate(BacktrackParams()).toList()
        // 3 unit tasks, machine of length 3 (slots {0, 1, 2}) → 3! = 6 permutations.
        assertEquals(6, samples.size, "expected 6 disjunctive schedules, got ${samples.size}")
        // Each is a valid permutation.
        for (s in samples) {
            val occ = BooleanArray(3)
            for (i in 0 until 3) {
                val slot = s.ints[i]
                assertTrue(slot in 0..2, "out-of-range slot $slot in ${s.ints.toList()}")
                assertTrue(!occ[slot], "double-booked at slot $slot in ${s.ints.toList()}")
                occ[slot] = true
            }
        }
    }

    @Test
    fun `LS finds a feasible disjunctive schedule`() {
        val problem = threeUnitTasks()
        val solver = LocalSearchSolver(
            problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 17L)).assignment
        assertNotNull(sample)
        val starts = sample.ints
        val occ = BooleanArray(3)
        for (i in 0 until 3) {
            val slot = starts[i]
            assertTrue(!occ[slot], "double-booked at slot $slot in ${starts.toList()}")
            occ[slot] = true
        }
    }

    @Test
    fun `pure pairwise infeasibility is caught`() {
        // Two tasks both must be first: each has lst < other's est + dur.
        // Task 0: dom [5, 5] duration 1 (must run at t=5, finishes t=6).
        // Task 1: dom [5, 5] duration 1 — same! Clearly infeasible.
        val factor = Disjunctive(starts = intArrayOf(0, 1), durations = intArrayOf(1, 1))
        val problem = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(5, 5), IntDomain(5, 5)),
            factors = listOf(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Unsat, "two tasks pinned at same time must fail; got $result")
    }
}
