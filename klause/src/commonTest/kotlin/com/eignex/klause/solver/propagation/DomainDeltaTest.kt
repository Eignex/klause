package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.AllDifferent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Oracle for [DomainDelta]: on every fire, the values it reports removed must exactly equal the
 * watched var's domain *size-drop* since the probe's previous fire — a relative invariant that
 * holds iff the cursor reports the right delta AND rolls back correctly with the search. Driven by
 * an [AllDifferent] that prunes and backtracks under full enumeration; a wrong or non-reverting
 * delta trips the in-propagate `check` and fails the test.
 */
class DomainDeltaTest {

    /** Observer factor (prunes nothing, always satisfied) that validates the delta on its var. */
    private class DeltaProbe(private val v: Int) : Factor {
        override val boolVars: IntArray = EmptyIntArray
        override val intVars: IntArray = intArrayOf(v)
        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = DeltaProbe(intMap[v])

        private class Cursor(val delta: DomainDelta, val lastSize: RevInt)

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val cur = state.intDomains[v]
            val c = (state.refPayload[factorId] as? Cursor)
                ?: Cursor(DomainDelta(state, cur), RevInt(state, cur.size)).also { state.refPayload[factorId] = it }
            var reported = 0
            c.delta.forEachRemoved(cur) { reported++ }
            check(reported == c.lastSize.value - cur.size) {
                "var $v: delta reported $reported removed, but size dropped ${c.lastSize.value - cur.size}"
            }
            c.lastSize.set(cur.size)
            return true
        }
    }

    @Test
    fun `delta equals the per-fire domain size-drop across search and backtrack`() {
        val n = 3
        val factors = ArrayList<Factor>()
        factors.add(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = n + 1)) // [0..3], drives prunes
        for (v in 0 until n) factors.add(DeltaProbe(v))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, n) },
            factors = factors.toTypedArray(),
        )
        val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
        val solutions = BacktrackSolver(problem).enumerate(params).take(100_000)
            .map { it.ints.toList() }.toHashSet()
        // 3 distinct values from {0..3} in order = 4*3*2 = 24; the probes must not have perturbed it,
        // and their in-propagate delta invariant must have held throughout the search.
        assertEquals(24, solutions.size, "AllDifferent over 3 vars in [0..3] has 24 ordered solutions")
    }
}
