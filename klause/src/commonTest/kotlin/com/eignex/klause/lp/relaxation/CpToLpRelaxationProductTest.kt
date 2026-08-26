package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Product McCormick envelope: `result = a·b`. The envelope is a *relaxation*, so its LP bound must
 * be **sound** — never cut off the true bilinear optimum over the box (an over-tight product hull is
 * the soundness failure). Layout: var 0 = a, 1 = b, 2 = result.
 */
class CpToLpRelaxationProductTest {

    private val eps = 1e-7

    private fun problem(a: IntDomain, b: IntDomain): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(a, b, IntDomain(-100, 100)),
        factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
    )

    private fun build(p: Problem, obj: LinearObjective, mcCormick: Boolean) =
        CpToLpRelaxation(p, obj, productMcCormick = mcCormick).build(PropagationSession(p))

    @Test
    fun `mccormick result bounds are sound`() {
        // a ∈ [1,3], b ∈ [2,4], result = a·b ∈ [2,12].
        val p = problem(IntDomain(1, 3), IntDomain(2, 4))
        val min = solveLp(build(p, LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L)), mcCormick = true).model)
        assertEquals(LpVerdict.OPTIMAL, min.status)
        assertTrue(min.objectiveValue <= 2.0 + eps, "UNSOUND: LP min ${min.objectiveValue} above bilinear min 2")
        // maximize result ⇔ minimize −result; true bilinear max = 12.
        val max = solveLp(build(p, LinearObjective(intCoefficients = longArrayOf(0L, 0L, -1L)), mcCormick = true).model)
        assertEquals(LpVerdict.OPTIMAL, max.status)
        assertTrue(max.objectiveValue <= -12.0 + eps, "UNSOUND: LP max ${-max.objectiveValue} below bilinear max 12")
    }

    @Test
    fun `mccormick stays sound for a square`() {
        // a = b ∈ [1,4], result = a² ∈ [1,16]. The envelope collapses to the secant/tangent relaxation.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 4), IntDomain(-100, 100)),
            factors = arrayOf<Factor>(Product(a = 0, b = 0, result = 1)),
        )
        val sol = solveLp(
            CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(0L, 1L)), productMcCormick = true)
                .build(PropagationSession(p)).model,
        )
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertTrue(sol.objectiveValue <= 1.0 + eps, "UNSOUND: square LP min ${sol.objectiveValue} above a² min 1")
    }

    @Test
    fun `mccormick actually emits the envelope rows`() {
        val p = problem(IntDomain(1, 3), IntDomain(2, 4))
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L))
        val withMc = build(p, obj, mcCormick = true)
        val without = build(p, obj, mcCormick = false)
        assertTrue(withMc.model.m >= without.model.m + 4, "McCormick adds four envelope rows")
    }
}
