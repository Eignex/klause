package com.eignex.klause.factor.scheduling

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Disjunctive
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisjunctivePropagatorTest {

    @Test
    fun `energetic-window conflict reason cites only the tasks packed into the overloaded window`() {
        // Four unit tasks over starts [0,9] (globally schedulable). A decision squeezes tasks 0,1,2
        // to start ≤ 1, packing three unit jobs into the length-2 window [0,2) — an edge-finding
        // overload with no compulsory overlap, so the mutual-precedence/profile paths miss it. The
        // sharp reason must cite only tasks 0,1,2, never the idle task 3 (start ≥ 5), and be entailed.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(Disjunctive(starts = intArrayOf(0, 1, 2, 3), durations = intArrayOf(1, 1, 1, 1))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1))
        check(state.tightenIntMax(1, 1))
        check(state.tightenIntMax(2, 1))
        check(state.tightenIntMin(3, 5)) // idle task tightened so a coarse reason would cite it
        assertFalse(problem.propagators[0].propagate(state, 0))
        val reason = problem.propagators[0].conflictReason(state, 0)!!
        val citedVars = reason.map { state.atomIntVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it in setOf(0, 1, 2) }, "reason must cite only the packed tasks, got $citedVars")
        assertTrue(3 !in citedVars, "idle task 3 must not appear in the sharp reason")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "disjunctive-energetic")
    }

    @Test
    fun `mutual-precedence conflict reason is a sound nogood citing only the two tasks`() {
        // Three duration-3 tasks over starts [0,9] (globally schedulable at 0,3,6). A decision
        // squeezes task 0 and task 1 both to start ≤ 2: each would then have to run strictly after
        // the other — a contradiction implied by just those two starts. The sharp reason must cite
        // only vars 0 and 1, never the idle task 2 (tightened to start≥6), and must be entailed.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(Disjunctive(starts = intArrayOf(0, 1, 2), durations = intArrayOf(3, 3, 3))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 2))
        check(state.tightenIntMax(1, 2))
        check(state.tightenIntMin(2, 6)) // idle task tightened so a coarse reason would cite it
        assertFalse(problem.propagators[0].propagate(state, 0))
        val reason = problem.propagators[0].conflictReason(state, 0)!!
        val citedVars = reason.map { state.atomIntVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(
            citedVars.all { it == 0 || it == 1 },
            "reason must cite only the two conflicting tasks, got $citedVars",
        )
        assertTrue(2 !in citedVars, "idle task 2 must not appear in the sharp reason")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "disjunctive-precedence")
    }

    // --- From DisjunctiveConflictReasonTest ---

    @Test
    fun `overload conflict reason is a sound nonempty bound-atom witness`() {
        // Two unit-resource tasks, durations 3 and 3, starts in [0, 5]. A level-1 decision
        // squeezes both starts' max down to 2, so each task occupies a window [est, 2+3) = [0, 5)
        // of length 5 while their combined energy is 6 > 5 — an energetic overload.
        val factor = Disjunctive(starts = intArrayOf(0, 1), durations = intArrayOf(3, 3))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        assertTrue(state.tightenIntMax(0, 2) && state.tightenIntMax(1, 2), "squeeze starts to [0, 2]")
        assertTrue(!problem.propagators[0].propagate(state, 0), "the squeezed window must overload (energy 6 > 5)")

        val reason = problem.propagators[0].conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "overload must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
    }

    @Test
    fun `enumerate matches brute force under conflict learning const durations`() {
        // 3 tasks, durations 2, 2, 1 over starts 0..3 must not overlap. The window is tight, so
        // the solver hits many overload/precedence conflicts and learns clauses off the new
        // intVars antecedents — if any antecedent were unsound a feasible tuple would be pruned.
        val durs = intArrayOf(2, 2, 1)
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = Array(3) { IntDomain(0, 3) },
                factors = listOf<Factor>(Disjunctive(starts = intArrayOf(0, 1, 2), durations = durs)),
            )
            val brute = HashSet<List<Int>>()
            for (s0 in 0..3) {
                for (s1 in 0..3) {
                    for (s2 in 0..3) {
                        val s = intArrayOf(s0, s1, s2)
                        var ok = true
                        for (i in 0..2) {
                            for (j in i + 1..2) {
                                if (!(s[i] + durs[i] <= s[j] || s[j] + durs[j] <= s[i])) ok = false
                            }
                        }
                        if (ok) brute.add(listOf(s0, s1, s2))
                    }
                }
            }
            val found = BacktrackSolver(problem)
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100_000).map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "seed=$seed: disjunctive const-duration enumeration must match brute force")
        }
    }

    @Test
    fun `enumerate matches brute force with fixed duration vars`() {
        // Duration-var path: durationVars[i] are pinned singletons, so propagate reads intVars =
        // starts + durationVars and its tightenings/nogood must cite both. vars 0..2 = starts in
        // [0, 3]; vars 3..5 = durations fixed to 2, 1, 2.
        val durVals = intArrayOf(2, 1, 2)
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 6,
                intDomains = Array(6) { i ->
                    if (i < 3) IntDomain(0, 3) else IntDomain(durVals[i - 3], durVals[i - 3])
                },
                factors = listOf<Factor>(
                    Disjunctive(
                        starts = intArrayOf(0, 1, 2),
                        durations = intArrayOf(1, 1, 1), // ignored: durationVars takes precedence
                        durationVars = intArrayOf(3, 4, 5),
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (s0 in 0..3) {
                for (s1 in 0..3) {
                    for (s2 in 0..3) {
                        val s = intArrayOf(s0, s1, s2)
                        var ok = true
                        for (i in 0..2) {
                            for (j in i + 1..2) {
                                if (!(s[i] + durVals[i] <= s[j] || s[j] + durVals[j] <= s[i])) ok = false
                            }
                        }
                        if (ok) brute.add(listOf(s0, s1, s2, durVals[0], durVals[1], durVals[2]))
                    }
                }
            }
            val found = BacktrackSolver(problem)
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100_000).map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "seed=$seed: disjunctive var-duration enumeration must match brute force")
        }
    }

    // --- From DisjunctiveTest (CP tests) ---

    @Test
    fun `pairwise detectable precedence pushes the earliest start`() {
        val factor = Disjunctive(starts = intArrayOf(0, 1), durations = intArrayOf(3, 1))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        val unsatPin = problem.propagate(Assumptions(ints = mapOf(1 to 2)))
        assertTrue(unsatPin is PropagationResult.Unsat, "pinning task 1 at t=2 must fail; got $unsatPin")
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
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        assertEquals(4, result.ints[2], "edge-finding should pin task 2's start to 4; implied=${result.ints}")
    }

    @Test
    fun `edge-finding pins a task forced to come first by tightening its latest start`() {
        // Mirror of the "forced last" case, exercising the reflected-timeline (start.max)
        // sweep. Three duration-2 tasks. Tasks 0 and 1 occupy the late cluster dom [1, 4]
        // (est 1, lct 6, sum_dur 4 — fits [1, 6] with slack 1). Task 2 (dom [0, 4]) added to
        // the union overflows the window, so it must end before all of {0, 1}, forcing
        // start_2.max ≤ lct({0,1}) − sum_dur({0,1}) − dur_2 = 6 − 4 − 2 = 0. With dom_2 =
        // [0, 4] task 2 collapses to {0}.
        val factor = Disjunctive(starts = intArrayOf(0, 1, 2), durations = intArrayOf(2, 2, 2))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 4), IntDomain(1, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        assertEquals(0, result.ints[2], "edge-finding should pin task 2's start to 0; implied=${result.ints}")
    }

    @Test
    fun `edge-finding does not push a task that can run before the cluster`() {
        // Regression guard for the unsound Env(Θ)+e_i shortcut: task 0 is pinned to t=1
        // (mandatory part [1, 2)), task 1 (dur 1, dom [0, 3]) can legitimately run at t=0,
        // before task 0. A flat-add detection would wrongly force task 1 after task 0
        // (start ≥ 2); the sound Env(Θ ∪ {i}) insertion must leave t=0 reachable.
        val factor = Disjunctive(starts = intArrayOf(0, 1), durations = intArrayOf(1, 1))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val ok = problem.propagate(Assumptions(ints = mapOf(1 to 0)))
        assertTrue(ok is PropagationResult.Implied, "task 1 at t=0 (before task 0) must stay feasible; got $ok")
    }

    @Test
    fun `BacktrackSolver enumerates exactly the 6 disjunctive schedules of three unit tasks`() {
        val factor = Disjunctive(starts = intArrayOf(0, 1, 2), durations = intArrayOf(1, 1, 1))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val solver = BacktrackSolver(problem)
        val samples = solver.enumerate(BacktrackParams()).toList()
        assertEquals(6, samples.size, "expected 6 disjunctive schedules, got ${samples.size}")
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
    fun `pure pairwise infeasibility is caught`() {
        val factor = Disjunctive(starts = intArrayOf(0, 1), durations = intArrayOf(1, 1))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(5, 5), IntDomain(5, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Unsat, "two tasks pinned at same time must fail; got $result")
    }
}
