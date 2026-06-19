package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.global.LexLess
import com.eignex.klause.solver.factor.global.Sort
import com.eignex.klause.solver.factor.global.SymmetricAllDifferent
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * #623: the bound-only globals [Sort], [LexLess] and [SymmetricAllDifferent] are driven by typed
 * bound-event subscription (each `propagate` reads only `min`/`max`), so they skip interior
 * `VALUE_REMOVED` wakes. Each test punches interior holes mid-search via a co-constraint and confirms
 * the full solution set is unchanged.
 */
class SortLexSymDiffBoundsEventTest {

    private class ExcludeOnFix(val src: Int, val dst: Int) : Factor {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(src, dst)

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            // Explain the exclusion: dst != src.min holds *because* src is fixed to that value.
            // Citing src's singleton bounds keeps the recorded reason complete, so conflict analysis
            // cannot drop the premise (a null reason silently under-explains — see ElementDeltaTest).
            return if (d.min == d.max) {
                state.excludeIntValue(dst, d.min, state.composeIntVarAtomAntecedents(intArrayOf(src)))
            } else {
                true
            }
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ExcludeOnFix(intMap[src], intMap[dst])

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> =
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
            .take(100_000).map { it.ints.toList() }.toHashSet()

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
    fun `all three subscribe to only bound events`() {
        assertBoundOnly(
            Sort(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3)).initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
        assertBoundOnly(
            LexLess(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), strict = true).initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
        assertBoundOnly(
            SymmetricAllDifferent(xs = intArrayOf(0, 1, 2), indexOffset = 0).initialIntEventWatches,
            intArrayOf(0, 1, 2),
        )
    }

    @Test
    fun `sort with interior holes punched mid-search enumerates exactly brute force`() {
        // ys = sorted(xs) over xs=[0,1], ys=[2,3]; var 4 carved out of xs. Domains 0..2 (wide enough
        // for an interior hole). Brute: y0=min, y1=max of (x0,x1), and x0,x1 ≠ c.
        for (seed in 1L..3L) {
            val factors = listOf<Factor>(
                Sort(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3)),
                ExcludeOnFix(src = 4, dst = 0),
                ExcludeOnFix(src = 4, dst = 1),
            )
            val problem = Problem(0, 5, Array(5) { IntDomain(0, 2) }, factors)
            val brute = HashSet<List<Int>>()
            for (x0 in 0..2) {
                for (x1 in 0..2) {
                    for (y0 in 0..2) {
                        for (y1 in 0..2) {
                            for (c in 0..2) {
                                if (y0 == minOf(x0, x1) && y1 == maxOf(x0, x1) && x0 != c && x1 != c) {
                                    brute.add(listOf(x0, x1, y0, y1, c))
                                }
                            }
                        }
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "sort seed=$seed must match brute force")
        }
    }

    @Test
    fun `lexless with interior holes punched mid-search enumerates exactly brute force`() {
        // (x0,x1) <lex (y0,y1) strict; var 4 carved out of xs. Domains 0..2.
        for (seed in 1L..3L) {
            val factors = listOf<Factor>(
                LexLess(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), strict = true),
                ExcludeOnFix(src = 4, dst = 0),
                ExcludeOnFix(src = 4, dst = 1),
            )
            val problem = Problem(0, 5, Array(5) { IntDomain(0, 2) }, factors)
            val brute = HashSet<List<Int>>()
            for (x0 in 0..2) {
                for (x1 in 0..2) {
                    for (y0 in 0..2) {
                        for (y1 in 0..2) {
                            for (c in 0..2) {
                                val lex = x0 < y0 || (x0 == y0 && x1 < y1)
                                if (lex && x0 != c && x1 != c) brute.add(listOf(x0, x1, y0, y1, c))
                            }
                        }
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "lexless seed=$seed must match brute force")
        }
    }

    @Test
    fun `symmetric-alldiff with interior holes punched mid-search enumerates exactly brute force`() {
        // xs=[0,1,2] a self-inverse permutation (involution); var 3 carved out of xs[0],xs[1].
        for (seed in 1L..3L) {
            val factors = listOf<Factor>(
                SymmetricAllDifferent(xs = intArrayOf(0, 1, 2), indexOffset = 0),
                ExcludeOnFix(src = 3, dst = 0),
                ExcludeOnFix(src = 3, dst = 1),
            )
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 2) }, factors)
            val brute = HashSet<List<Int>>()
            for (a0 in 0..2) {
                for (a1 in 0..2) {
                    for (a2 in 0..2) {
                        for (c in 0..2) {
                            val a = intArrayOf(a0, a1, a2)
                            val perm = a0 != a1 && a0 != a2 && a1 != a2 // distinct ⇒ permutation of 0..2
                            val involution = perm && (0..2).all { a[a[it]] == it }
                            if (involution && a0 != c && a1 != c) brute.add(listOf(a0, a1, a2, c))
                        }
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "symmetric-alldiff seed=$seed must match brute force")
        }
    }
}
