package com.eignex.klause.solver.lp.relaxation

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.table.Element
import com.eignex.klause.solver.lp.LpSolution
import com.eignex.klause.solver.lp.LpStatus
import com.eignex.klause.solver.lp.solveLp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
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
                Element(idx = 0, result = 1, arr = intArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
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
        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertTrue(sol.objectiveValue <= 2.0 + eps, "UNSOUND: LP min ${sol.objectiveValue} exceeds integer optimum 2")
    }

    @Test
    fun `var-array hull is exact when the index is fixed`() {
        // idx pinned to position 1 ⇒ result = arr[1] ∈ [2,8]; single selector is integral, so LP min = 2.
        val sol = minResult(IntDomain(1, 1), IntDomain(4, 10), IntDomain(2, 8))
        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, eps)
    }

    @Test
    fun `var-array hull is exact for the other fixed index`() {
        // idx pinned to position 0 ⇒ result = arr[0] ∈ [4,10]; LP min = 4.
        val sol = minResult(IntDomain(0, 0), IntDomain(4, 10), IntDomain(2, 8))
        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(4.0, sol.objectiveValue, eps)
    }

    @Test
    fun `var-array hull bound stays sound when both arrays are tightened high`() {
        // arr[0] ∈ [6,9], arr[1] ∈ [5,7], idx ∈ {0,1}. True integer min(result) = 5.
        val sol = minResult(IntDomain(0, 1), IntDomain(6, 9), IntDomain(5, 7))
        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertTrue(sol.objectiveValue <= 5.0 + eps, "UNSOUND: LP min ${sol.objectiveValue} exceeds integer optimum 5")
    }
}
