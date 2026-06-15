package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #623/#624: the variable-array [Element] consumes the per-factor dirty-variable delta, scoping its
 * unchanged-domains gate to the variables the engine reports as changed (instead of an O(arity)
 * ref-scan). If the delta ever dropped a changed array/index/result variable, the GAC sweep would be
 * skipped when it shouldn't and an inconsistent assignment would slip through — so the enumeration
 * test punches interior holes into the array variables mid-search via a co-constraint and checks the
 * solution set still equals brute force.
 */
class ElementDeltaTest {

    private class ExcludeOnFix(val src: Int, val dst: Int) : Factor {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(src, dst)

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            return if (d.min == d.max) state.excludeIntValue(dst, d.min) else true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ExcludeOnFix(intMap[src], intMap[dst])
    }

    @Test
    fun `variable-array element subscribes to all kinds and consumes the delta`() {
        val varArr = Element(idx = 0, result = 1, arr = intArrayOf(2, 3), arrIsVars = true, indexOffset = 0)
        assertTrue(varArr.consumesIntEventDelta, "var-array element must consume the dirty-var delta")
        val watches = varArr.initialIntEventWatches
        assertTrue(watches != null)
        // every distinct variable subscribed to all four kinds
        val byVar = watches.groupBy { IntEvent.intVarOf(it) }
        for ((_, packs) in byVar) {
            assertEquals(
                setOf(IntEvent.LB_RAISED, IntEvent.UB_LOWERED, IntEvent.VALUE_REMOVED, IntEvent.FIXED),
                packs.map { IntEvent.kindOf(it) }.toSet(),
            )
        }
        assertEquals(setOf(0, 1, 2, 3), byVar.keys, "all of idx/result/array vars subscribed")

        // The constant-array path keeps occurrence wakeup (its own reversible domRef fast path).
        val constArr = Element(idx = 0, result = 1, arr = intArrayOf(5, 6, 7), arrIsVars = false, indexOffset = 0)
        assertNull(constArr.initialIntEventWatches)
        assertFalse(constArr.consumesIntEventDelta)
    }

    @Test
    fun `delta-gated variable-array element stays sound with interior holes punched mid-search`() {
        // result = arr[idx] over arr=[v2,v3], plus a co-constraint carving var4's fixed value out of
        // v2/v3 — punching interior holes the element's gate must still react to. vars: 0=idx, 1=result,
        // 2=v2, 3=v3, 4=c.
        for (seed in 1L..6L) {
            val factors = listOf<Factor>(
                Element(idx = 0, result = 1, arr = intArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
                ExcludeOnFix(src = 4, dst = 2),
                ExcludeOnFix(src = 4, dst = 3),
            )
            val doms = arrayOf(IntDomain(0, 1), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3))
            val problem = Problem(0, 5, doms, factors)
            val brute = HashSet<List<Int>>()
            for (idx in 0..1) {
                for (res in 0..3) {
                    for (v2 in 0..3) {
                        for (v3 in 0..3) {
                            for (c in 0..3) {
                                val selected = if (idx == 0) v2 else v3
                                if (res == selected && v2 != c && v3 != c) brute.add(listOf(idx, res, v2, v3, c))
                            }
                        }
                    }
                }
            }
            val found = BacktrackSolver(problem)
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100_000).map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "seed=$seed: delta-gated element + interior holes must match brute")
        }
    }
}
