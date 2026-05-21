package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
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

class CumulativeTest {

    /**
     * 3 tasks, each duration 2, resource 1; capacity 1; start domain [0, 4]. Only
     * non-overlapping schedules are feasible — three tasks back-to-back occupy [0, 6).
     */
    private fun threeTasksUnary(): Problem {
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = intArrayOf(2, 2, 2),
            resources = intArrayOf(1, 1, 1),
            capacity = 1,
        )
        return Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `overload check detects energy infeasibility that time-tabling misses`() {
        // 3 tasks, each duration 3, resource 1; capacity 1; start domains all [0, 3].
        // No task has a compulsory part on its own (each window is 6 wide with duration 3),
        // so time-tabling alone observes no mandatory overlap and accepts the state.
        // Total energy = 3 × 3 = 9, available capacity × span = 1 × (3+3 − 0) = 6. The
        // overload check fires.
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = intArrayOf(3, 3, 3),
            resources = intArrayOf(1, 1, 1),
            capacity = 1,
        )
        val p = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val baked = p.baked
        assertTrue(
            baked is PropagationResult.Unsat,
            "overload check should mark this as Unsat at bake time, got $baked",
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
        // 2 tasks duration 3 resource 2, capacity 3. Tasks at start 0 and 1:
        // usage over t in {0, 1, 2, 3} is {2, 4, 4, 2} → overage at t=1,2 is 1 each → total 2.
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = intArrayOf(3, 3),
            resources = intArrayOf(2, 2),
            capacity = 3,
        )
        val problem = Problem(
            numBoolVars = 0, numIntVars = 2,
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
        // Initial: all at 0 → all three overlap, big overage.
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        val before = state.intPayload[0]
        // Apply moves: spread them out to 0, 2, 4. Each move is committed individually.
        state.apply(com.eignex.klause.solver.Move.IntSet(1, 2))
        state.apply(com.eignex.klause.solver.Move.IntSet(2, 4))
        val afterIncr = state.intPayload[0]
        // Now do a fresh recompute and confirm intPayload matches.
        val fresh = LocalSearchState(problem, Random(0))
        fresh.assignment.setInt(0, 0); fresh.assignment.setInt(1, 2); fresh.assignment.setInt(2, 4)
        fresh.recompute()
        assertEquals(0, afterIncr, "spread schedule should be feasible")
        assertEquals(fresh.intPayload[0], afterIncr, "incremental apply must agree with recompute")
        assertTrue(before > 0, "all-at-zero must start violated")
    }

    @Test
    fun `propagator fails when forced overlap exceeds capacity`() {
        // 2 tasks duration 2 resource 2, capacity 2; both pinned to start at 0 → usage 4 > 2.
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = intArrayOf(2, 2),
            resources = intArrayOf(2, 2),
            capacity = 2,
        )
        val problem = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 0, 1 to 0)))
        assertTrue(result is PropagationResult.Unsat, "double-booking at capacity must fail; got $result")
    }

    @Test
    fun `propagator shaves a start that would overlap a mandatory part`() {
        // Task 0: duration 4, resource 1, start domain [0, 0] (fixed) → mandatory [0, 4).
        // Task 1: duration 2, resource 1, start domain [0, 4]. Capacity 1.
        // Time-tabling shaves any start s < 4 (would coincide with the mandatory part), so
        // task 1's domain collapses to the singleton {4}, surfacing as Implied.ints[1] == 4.
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = intArrayOf(4, 2),
            resources = intArrayOf(1, 1),
            capacity = 1,
        )
        val problem = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        assertEquals(4, result.ints[1], "task 1 must be pinned to t=4 after time-tabling shaves earlier starts")
    }

    @Test
    fun `propagator rejects a pin that would force overlap with a mandatory part`() {
        // Same scenario but pin task 1 to start at 2: overlaps task 0's mandatory part.
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = intArrayOf(4, 2),
            resources = intArrayOf(1, 1),
            capacity = 1,
        )
        val problem = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 6)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions(ints = mapOf(1 to 2)))
        assertTrue(result is PropagationResult.Unsat, "overlap with mandatory part must fail; got $result")
    }

    @Test
    fun `BacktrackSolver finds a feasible 3-task unary schedule`() {
        val problem = threeTasksUnary()
        val solver = BacktrackSolver(problem)
        val sample = solver.sample(BacktrackParams()).assignment
        assertNotNull(sample, "BacktrackSolver should find a feasible schedule")
        // Verify the assignment is in fact non-overlapping.
        val starts = sample.ints
        val occ = IntArray(8)
        for (i in 0 until 3) {
            for (t in starts[i] until starts[i] + 2) {
                if (t in occ.indices) occ[t]++
            }
        }
        for (t in occ.indices) assertTrue(occ[t] <= 1, "unary capacity broken at t=$t in $starts")
    }

    @Test
    fun `LS finds a feasible schedule for the 3-task unary problem`() {
        val problem = threeTasksUnary()
        val solver = LocalSearchSolver(
            problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 11L)).assignment
        assertNotNull(sample, "LS should find a feasible cumulative schedule")
        val starts = sample.ints
        val occ = IntArray(8)
        for (i in 0 until 3) {
            for (t in starts[i] until starts[i] + 2) {
                if (t in occ.indices) occ[t]++
            }
        }
        for (t in occ.indices) assertTrue(occ[t] <= 1, "unary capacity broken at t=$t in ${starts.toList()}")
    }

    @Test
    fun `edge-finding tightens a start past where time-tabling can reach`() {
        // A, B: duration 2, resource 2, start ∈ [0, 2]. Neither has a compulsory part
        // (lst=2, ect=2). C: duration 2, resource 3, start ∈ [0, 10]. Capacity 3.
        //
        // Time-tabling builds no mandatory profile (no compulsory parts exist) and the
        // overload check passes (energy 4+4+6=14 ≤ 3·12=36). But Θ = {A,B} has envelope
        // C·est(Ω)+e(Ω) maximised at Ω={A,B} → 0+8 = 8. With τ=lct(Θ)=4 and C with c=3:
        //   detection: 8 + 6 > 3·4 = 12  ✓
        //   update:    est(C) ≥ ⌈(8 − (3−3)·4) / 3⌉ = ⌈8/3⌉ = 3.
        // C's wide upper bound (10) keeps the problem feasible after the deduction so
        // the result is Implied(intMin=3 for C), not Unsat.
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = intArrayOf(2, 2, 2),
            resources = intArrayOf(2, 2, 3),
            capacity = 3,
        )
        val problem = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 10)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        assertEquals(3, result.intMinOrNullCompat(2),
            "edge-finding should push C's start min from 0 to 3")
    }

    @Test
    fun `edge-finding is silent when no deduction applies`() {
        // Three identical tasks comfortably fitting their windows. The edge-finder
        // should not produce any spurious bound tightenings.
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = intArrayOf(1, 1, 1),
            resources = intArrayOf(1, 1, 1),
            capacity = 3,
        )
        val problem = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        assertEquals(null, result.intMinOrNullCompat(0))
        assertEquals(null, result.intMinOrNullCompat(1))
        assertEquals(null, result.intMinOrNullCompat(2))
    }
}
