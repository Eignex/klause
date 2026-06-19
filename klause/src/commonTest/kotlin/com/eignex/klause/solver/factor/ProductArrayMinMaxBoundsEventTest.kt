package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.arithmetic.ArrayMinMax
import com.eignex.klause.solver.factor.arithmetic.Product
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * #623: [Product] and [ArrayMinMax] are interval propagators (read only `min`/`max`), so they are
 * driven by typed bound-event subscription and skip interior `VALUE_REMOVED` wakes. The enumeration
 * tests punch interior holes mid-search via a co-constraint and confirm the full solution set is
 * unchanged — the skipped wakes lose no deduction.
 */
class ProductArrayMinMaxBoundsEventTest {

    private class ExcludeOnFix(val src: Int, val dst: Int) : Factor {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(src, dst)

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            // Explain the exclusion: dst != src.min holds *because* src is fixed to that value.
            // Citing src's singleton bounds keeps the recorded reason complete, so conflict
            // analysis cannot drop the premise (a null reason silently under-explains).
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
            listOf(
                v to IntEvent.LB_RAISED,
                v to IntEvent.UB_LOWERED,
            )
        }.toSet()
        assertEquals(expected, pairs)
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
        )
    }

    @Test
    fun `product and array-minmax subscribe to only bound events`() {
        assertBoundOnly(Product(a = 0, b = 1, result = 2).initialIntEventWatches, intArrayOf(0, 1, 2))
        assertBoundOnly(
            ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = true).initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
    }

    @Test
    fun `product with interior holes punched mid-search enumerates exactly brute force`() {
        // result = a*b, plus a co-constraint carving x3's fixed value out of a and b (interior holes
        // the product is not woken for). a,b,c ∈ 0..3, result ∈ 0..9.
        for (seed in 1L..16L) {
            val factors = listOf<Factor>(
                Product(a = 0, b = 1, result = 2),
                ExcludeOnFix(src = 3, dst = 0),
                ExcludeOnFix(src = 3, dst = 1),
            )
            val doms = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 9), IntDomain(0, 3))
            val problem = Problem(0, 4, doms, factors)
            val brute = HashSet<List<Int>>()
            for (a in 0..3) {
                for (b in 0..3) {
                    for (r in 0..9) {
                        for (c in 0..3) {
                            if (r == a * b && a != c && b != c) brute.add(listOf(a, b, r, c))
                        }
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "product seed=$seed must match brute force")
        }
    }

    @Test
    fun `array-max with interior holes punched mid-search enumerates exactly brute force`() {
        // result = max(x0,x1), plus a co-constraint carving x3's fixed value out of x0 and x1.
        for (seed in 1L..16L) {
            val factors = listOf<Factor>(
                ArrayMinMax(result = 2, xs = intArrayOf(0, 1), max = true),
                ExcludeOnFix(src = 3, dst = 0),
                ExcludeOnFix(src = 3, dst = 1),
            )
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 3) }, factors)
            val brute = HashSet<List<Int>>()
            for (x0 in 0..3) {
                for (x1 in 0..3) {
                    for (r in 0..3) {
                        for (c in 0..3) {
                            if (r == maxOf(x0, x1) && x0 != c && x1 != c) brute.add(listOf(x0, x1, r, c))
                        }
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "array-max seed=$seed must match brute force")
        }
    }
}
