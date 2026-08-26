package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.table.Element
import com.eignex.klause.lp.LpSolution
import com.eignex.klause.lp.LpVerdict
import com.eignex.klause.lp.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Variable-array Element big-M linearization: `result = arr[idx]` where `arr` holds int-var ids.
 * The hull is a *relaxation*, so its LP bound on a minimized objective must be **sound** — never above
 * the true integer optimum (an over-tight hull cutting off the optimal assignment is the soundness failure).
 * When `idx` is fixed to a single position the single selector is integral, so the hull is exact.
 *
 * Layout: var 0 = idx, 1 = result, 2 = arr[0], 3 = arr[1].
 */
class CpToLpRelaxationVarElementTest {

    private val eps = 1e-7

    private fun minResult(idx: IntDomain, a0: IntDomain, a1: IntDomain): LpSolution {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(idx, IntDomain(0, 50), a0, a1),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 1L, 0L, 0L))
        val r = CpToLpRelaxation(p, obj, elementHull = true).build(PropagationSession(p))
        return solveLp(r.model)
    }

    @Test
    fun `var-array hull bound is sound for a free index`() {
        // arr[0] ∈ [4,10], arr[1] ∈ [2,8], idx ∈ {0,1}. True integer min(result) = 2 (idx=1, arr[1]=2).
        val sol = minResult(IntDomain(0, 1), IntDomain(4, 10), IntDomain(2, 8))
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertTrue(sol.objectiveValue <= 2.0 + eps, "UNSOUND: LP min ${sol.objectiveValue} exceeds integer optimum 2")
    }

    @Test
    fun `var-array hull is exact when the index is fixed`() {
        // arr[0] ∈ [4,10], arr[1] ∈ [2,8]. Pinning idx leaves a single integral selector, so the LP min
        // is exactly the pinned entry's own minimum.
        listOf(0 to 4.0, 1 to 2.0).forEach { (position, expected) ->
            val sol = minResult(IntDomain(position.toLong(), position.toLong()), IntDomain(4, 10), IntDomain(2, 8))
            assertEquals(LpVerdict.OPTIMAL, sol.status, "idx=$position: status")
            assertEquals(expected, sol.objectiveValue, eps, "idx=$position: LP min is arr[$position]'s minimum")
        }
    }

    @Test
    fun `var-array hull bound stays sound when both arrays are tightened high`() {
        // arr[0] ∈ [6,9], arr[1] ∈ [5,7], idx ∈ {0,1}. True integer min(result) = 5.
        val sol = minResult(IntDomain(0, 1), IntDomain(6, 9), IntDomain(5, 7))
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertTrue(sol.objectiveValue <= 5.0 + eps, "UNSOUND: LP min ${sol.objectiveValue} exceeds integer optimum 5")
    }
}
