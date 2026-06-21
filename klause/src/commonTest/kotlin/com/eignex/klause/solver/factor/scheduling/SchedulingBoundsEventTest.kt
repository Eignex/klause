package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.arithmetic.ReifiedLinear
import com.eignex.klause.solver.factor.scheduling.Cumulative
import com.eignex.klause.solver.factor.scheduling.Diffn
import com.eignex.klause.solver.factor.scheduling.Disjunctive
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * #623: the scheduling/packing globals [Cumulative], [Diffn] and [Disjunctive] are bound-only
 * (their `propagate` reads only `min`/`max`), so they subscribe to `LB_RAISED`/`UB_LOWERED` and skip
 * interior `VALUE_REMOVED` wakes. Subscription shape is checked for all three; the enumeration test
 * punches interior holes into a `Disjunctive`'s start variable mid-search and confirms the
 * non-overlap solution set is unchanged. (Existing CumulativeTest/DiffnTest/DisjunctiveTest oracles
 * also re-run under the new wakeup.)
 */
class SchedulingBoundsEventTest {

    private class ExcludeOnFix(val src: Int, val dst: Int) : Factor {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(src, dst)

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            return if (d.min == d.max) state.excludeIntValue(dst, d.min) else true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ExcludeOnFix(intMap[src], intMap[dst])

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
    }

    private fun assertBoundOnly(watches: IntArray?, vars: IntArray) {
        val pairs = watches!!.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        val expected = vars.toHashSet().flatMap { v ->
            listOf(v to IntEvent.LB_RAISED, v to IntEvent.UB_LOWERED)
        }.toSet()
        assertEquals(expected, pairs)
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
        )
    }

    @Test
    fun `cumulative diffn disjunctive subscribe to only bound events`() {
        assertBoundOnly(
            Cumulative(
                starts = intArrayOf(0, 1),
                durations = intArrayOf(2, 2),
                resources = intArrayOf(1, 1),
                capacity = 1,
            )
                .asPropagator().initialIntEventWatches,
            intArrayOf(0, 1),
        )
        assertBoundOnly(
            Diffn(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), widths = intArrayOf(1, 1), heights = intArrayOf(1, 1))
                .asPropagator().initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
        assertBoundOnly(
            Disjunctive(
                starts = intArrayOf(0, 1, 2),
                durations = intArrayOf(2, 1, 1),
            ).asPropagator().initialIntEventWatches,
            intArrayOf(0, 1, 2),
        )
        // Reified linear's int reasoning is interval-based (linearSumRange + propagateLinearBounds);
        // it subscribes its term vars to LB/UB only — the indicator bool keeps its Boolean wakeup.
        val reified = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(1, 1),
            vars = intArrayOf(1, 2),
            op = LinearOp.LE,
            bound = 3,
        )
        assertBoundOnly(reified.asPropagator().initialIntEventWatches, intArrayOf(1, 2))
    }

    @Test
    fun `disjunctive with interior holes punched mid-search enumerates exactly brute force`() {
        // 3 tasks (durations 2,1,1) over starts 0..3 must not overlap; a co-constraint carves var3's
        // fixed value out of starts 0 and 1 — punching interior holes the bound-only filter ignores.
        val durs = intArrayOf(2, 1, 1)
        for (seed in 1L..5L) {
            val factors = listOf<Factor>(
                Disjunctive(starts = intArrayOf(0, 1, 2), durations = durs),
                ExcludeOnFix(src = 3, dst = 0),
                ExcludeOnFix(src = 3, dst = 1),
            )
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 3) }, factors)
            val brute = HashSet<List<Int>>()
            for (s0 in 0..3) {
                for (s1 in 0..3) {
                    for (s2 in 0..3) {
                        for (c in 0..3) {
                            val s = intArrayOf(s0, s1, s2)
                            var ok = true
                            for (i in 0..2) {
                                for (j in i + 1..2) {
                                    val noOverlap = s[i] + durs[i] <= s[j] || s[j] + durs[j] <= s[i]
                                    if (!noOverlap) ok = false
                                }
                            }
                            if (ok && s0 != c && s1 != c) brute.add(listOf(s0, s1, s2, c))
                        }
                    }
                }
            }
            val found = BacktrackSolver(problem)
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100_000).map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "seed=$seed: disjunctive + interior holes must match brute force")
        }
    }
}
