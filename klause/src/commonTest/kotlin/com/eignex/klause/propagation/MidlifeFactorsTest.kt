package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The incremental-presolve mid-life factor overlay ([MidlifeFactors]), exercised through the
 * [PropagationState] API: appending a factor and
 * tombstoning another between propagation rounds must reach the same root fixpoint as building the
 * final factor set from scratch. Sound because the propagators are monotone, so the greatest
 * fixpoint is unique regardless of the order factors are introduced or the ids they carry.
 */
class MidlifeFactorsTest {

    // Three int vars, each 0..10. A fresh array per call — Problem construction folds root deductions
    // into the domains it is given, so sharing one array across builds would cross-contaminate.
    private fun domains() = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10))

    private fun leq(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.LE, bound)

    private fun intBounds(state: PropagationState) =
        (0 until state.problem.numIntVars).map { state.intDomains[it].min to state.intDomains[it].max }

    /** Bake [factors] from scratch (non-incremental) and read the resulting int bounds. */
    private fun freshBounds(factors: List<Factor>): List<Pair<Long, Long>> {
        val state = PropagationState(Problem(0, 3, domains(), factors), Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        return intBounds(state)
    }

    @Test
    fun `an added mid-life factor propagates like a fresh build`() {
        val base = leq(intArrayOf(1), intArrayOf(0), 5) // x0 <= 5
        val added = leq(intArrayOf(1, -1), intArrayOf(1, 0), 0) // x1 <= x0

        val state = PropagationState(Problem(0, 3, domains(), listOf(base)), Assumptions.None, incremental = true)
        assertNull(state.runToFixpoint(allFactors = true))
        state.addMidlifeFactor(added)
        assertNull(state.runToFixpoint(allFactors = true))

        assertEquals(freshBounds(listOf(base, added)), intBounds(state))
        assertEquals(5, state.intDomains[1].max) // x1 tightened through x0
    }

    @Test
    fun `a chain of mid-life factors propagates transitively like a fresh build`() {
        val base = leq(intArrayOf(1), intArrayOf(0), 5) // x0 <= 5
        val g = leq(intArrayOf(1, -1), intArrayOf(1, 0), 0) // x1 <= x0
        val h = leq(intArrayOf(1, -1), intArrayOf(2, 1), 0) // x2 <= x1

        val state = PropagationState(Problem(0, 3, domains(), listOf(base)), Assumptions.None, incremental = true)
        assertNull(state.runToFixpoint(allFactors = true))
        state.addMidlifeFactor(g)
        state.addMidlifeFactor(h)
        assertNull(state.runToFixpoint(allFactors = true))

        assertEquals(freshBounds(listOf(base, g, h)), intBounds(state))
        assertEquals(5, state.intDomains[2].max) // x2 tightened through the whole chain
    }

    @Test
    fun `a tombstoned mid-life factor does not propagate`() {
        val added = leq(intArrayOf(1), intArrayOf(0), 5) // x0 <= 5, never allowed to fire

        val state = PropagationState(Problem(0, 3, domains(), emptyList()), Assumptions.None, incremental = true)
        assertNull(state.runToFixpoint(allFactors = true))
        val fid = state.addMidlifeFactor(added)
        state.tombstoneFactor(fid)
        assertNull(state.runToFixpoint(allFactors = true))

        assertEquals(freshBounds(emptyList()), intBounds(state))
        assertEquals(10, state.intDomains[0].max) // untouched — the tombstoned factor stayed inert
    }

    @Test
    fun `tombstoning a redundant base factor matches a fresh build without it`() {
        val kept = leq(intArrayOf(1), intArrayOf(0), 5) // x0 <= 5
        val redundant = leq(intArrayOf(1), intArrayOf(0), 7) // x0 <= 7, implied by kept

        val state = PropagationState(
            Problem(0, 3, domains(), listOf(kept, redundant)),
            Assumptions.None,
            incremental = true,
        )
        assertNull(state.runToFixpoint(allFactors = true))
        state.tombstoneFactor(1) // drop the redundant one by id
        assertNull(state.runToFixpoint(allFactors = true))

        assertEquals(freshBounds(listOf(kept)), intBounds(state))
        assertEquals(5, state.intDomains[0].max)
    }
}
