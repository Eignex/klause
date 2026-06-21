package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.scheduling.Cumulative
import com.eignex.klause.solver.factor.scheduling.Disjunctive
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #651: [Disjunctive] overrides [Factor.conflictReason] with the bound-atom nogood of every
 * read int var (starts + fixed duration vars), mirroring its [Cumulative] backing rather than a
 * coarse bool-pins clause that would collapse to chronological backtrack once an int decision is
 * on the trail. The shave/precedence/edge-finding tightenings
 * are also unified to cite `intVars` (the time-table min-tighten previously passed `null`, severing
 * the resolution chain). Tests: (1) the reason is a sound non-empty witness — every literal false
 * at conflict time; (2) full enumeration under CDCL learning still matches brute force, so the new
 * antecedents/nogood prune no feasible assignment (the soundness arbiter).
 */
class DisjunctiveConflictReasonTest {

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
}
