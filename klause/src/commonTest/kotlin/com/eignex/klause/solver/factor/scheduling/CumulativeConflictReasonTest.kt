package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.scheduling.Cumulative
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #729: [Cumulative] now emits a sharp pointwise time-tabling explanation instead of the
 * constraint-wide "every read var's bounds" reason. On the all-constant / all-mandatory path a
 * profile-overload conflict cites only the tasks whose compulsory part covers the overloaded time
 * point — and only their generalised window bounds — so the learned nogood generalises across the
 * search instead of matching one dead-end state. Tests: (1) the reason is a sound non-empty witness
 * that excludes tasks not covering the overload; (2) full enumeration under CDCL learning still
 * matches brute force (the sharper clauses lose no feasible model).
 */
class CumulativeConflictReasonTest {

    @Test
    fun `profile-overload reason is a sound witness that omits non-covering tasks`() {
        // 3 tasks, duration 2, resource 2, capacity 2, starts in [0, 10]. Pin tasks 0 and 1 to t=0:
        // their compulsory parts [0,2) stack to level 4 > 2 at t=0 → profile overload. Task 2 is
        // pinned far away (t=8), so it does not cover the overloaded point and must not be cited.
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = intArrayOf(2, 2, 2),
            resources = intArrayOf(2, 2, 2),
            capacity = 2,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        assertTrue(state.setInt(0, 0) && state.setInt(1, 0) && state.setInt(2, 8), "pin the three starts")
        assertFalse(factor.propagate(state, 0), "tasks 0 and 1 double-book capacity at t=0 → infeasible")

        val reason = factor.conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
        // Sharp: only the two stacking tasks' upper-start bounds are cited (each `¬[start ≤ 0]`),
        // never task 2's bounds — that is the whole point of the pointwise explanation.
        assertEquals(2, reason.size, "pointwise reason cites only the two tasks covering the overload")
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> = BacktrackSolver(problem)
        .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
        .take(100_000)
        .map { it.ints.toList() }
        .toHashSet()

    @Test
    fun `enumerate matches brute force under learning`() {
        // 3 unit-demand tasks, duration 2, capacity 1, starts in [0, 5]. Capacity 1 forces the
        // occupied intervals [s, s+2) to be pairwise disjoint, i.e. pairwise |s_i − s_j| ≥ 2. The
        // tight domain makes the propagator overload and shave often, exercising the sharp reasons.
        val span = 5
        val dur = 2
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, span), IntDomain(0, span), IntDomain(0, span)),
                factors = arrayOf<Factor>(
                    Cumulative(
                        starts = intArrayOf(0, 1, 2),
                        durations = intArrayOf(dur, dur, dur),
                        resources = intArrayOf(1, 1, 1),
                        capacity = 1,
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (s0 in 0..span) {
                for (s1 in 0..span) {
                    for (s2 in 0..span) {
                        val starts = intArrayOf(s0, s1, s2)
                        var ok = true
                        for (a in 0..2) {
                            for (b in a + 1..2) {
                                if (abs(starts[a] - starts[b]) < dur) ok = false
                            }
                        }
                        if (ok) brute.add(listOf(s0, s1, s2))
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: cumulative enumerate must match brute force")
        }
    }

    @Test
    fun `enumerate matches brute force with variable resources`() {
        // The mspsp-shaped path: capacity 1, 0/1 resource vars (a task "uses" the resource only when
        // its var is 1). Two tasks, duration 2, starts in [0, 3], resource vars in [0, 1]. Infeasible
        // exactly when both use the resource (r=1) and their [s, s+2) intervals overlap (|s0−s1| < 2).
        // ints = [s0, s1, r0, r1]. This drives the variable-resource sharp reason (`¬[r_k ≥ 1]` cited).
        val span = 3
        val dur = 2
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = arrayOf(IntDomain(0, span), IntDomain(0, span), IntDomain(0, 1), IntDomain(0, 1)),
                factors = arrayOf<Factor>(
                    Cumulative(
                        starts = intArrayOf(0, 1),
                        durations = intArrayOf(dur, dur),
                        resources = intArrayOf(1, 1),
                        capacity = 1,
                        resourceVars = intArrayOf(2, 3),
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (s0 in 0..span) {
                for (s1 in 0..span) {
                    for (r0 in 0..1) {
                        for (r1 in 0..1) {
                            val overlap = abs(s0 - s1) < dur
                            if (!(overlap && r0 == 1 && r1 == 1)) brute.add(listOf(s0, s1, r0, r1))
                        }
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: var-resource cumulative must match brute force")
        }
    }
}
