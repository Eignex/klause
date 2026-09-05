package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpSolution
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
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
        // arr[0] ∈ [6,9], arr[1] ∈ [5,7] tightened high: true integer min(result) = 5.
        listOf(
            Triple(IntDomain(4, 10), IntDomain(2, 8), 2.0),
            Triple(IntDomain(6, 9), IntDomain(5, 7), 5.0),
        ).forEach { (a0, a1, optimum) ->
            val sol = minResult(IntDomain(0, 1), a0, a1)
            assertEquals(LpVerdict.OPTIMAL, sol.status)
            assertTrue(
                sol.objectiveValue <= optimum + eps,
                "UNSOUND: LP min ${sol.objectiveValue} exceeds integer optimum $optimum",
            )
        }
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

    /** `result = arr[idx]` over two variable entries, with the sides [openHi] marks as invented. */
    private fun openProblem(openHi: BooleanArray): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 50), IntDomain(4, 10), IntDomain(2, 8)),
        factors = arrayOf<Factor>(
            Element(idx = 0, result = 1, arr = longArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
        ),
        openIntHi = openHi,
    )

    @Test
    fun `an array entry the model leaves open loses only its own big-M pair`() {
        // Each M spans the result's root box and the entry's, so an invented endpoint on either makes the
        // pair a search restriction. The selectors and the index channel are untouched.
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 1L, 0L, 0L))
        val closed = openProblem(BooleanArray(4))
        val open = openProblem(booleanArrayOf(false, false, false, true))
        val closedRows = CpToLpRelaxation(closed, obj, elementHull = true).build(PropagationSession(closed)).model.m
        val openRows = CpToLpRelaxation(open, obj, elementHull = true).build(PropagationSession(open)).model.m
        assertEquals(closedRows - 2, openRows, "the open entry's two big-M rows are declined")
    }

    @Test
    fun `a result the model leaves open drops every big-M row`() {
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 1L, 0L, 0L))
        val closed = openProblem(BooleanArray(4))
        val open = openProblem(booleanArrayOf(false, true, false, false))
        val closedRows = CpToLpRelaxation(closed, obj, elementHull = true).build(PropagationSession(closed)).model.m
        val openRows = CpToLpRelaxation(open, obj, elementHull = true).build(PropagationSession(open)).model.m
        assertEquals(closedRows - 4, openRows, "no M bounds the gap, so only Σ y = 1 and the index channel stay")
    }
}
