package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #655 (Tranche C): the count-variable [GlobalCardinality] one-hot hull. Each variable picks one
 * value (a simplex `Σ_v z_iv = 1`) and the count rows read `Σ_i z_{i,cover(k)} = counts(k)`; the
 * assignment polytope is a product of simplices, hence integral, so the LP optimum of a linear
 * objective over the `xs` and count variables equals the true integer optimum — checked against
 * brute force — and a count in the objective gets an exact bound the bare propagator domain misses.
 */
class CpToLpRelaxationGccCountHullTest {

    private val eps = 1e-9

    @Test
    fun `a variable the model leaves open gets no count hull`() {
        // The selectors enumerate each variable's root box and the count rows read off it, so an invented
        // endpoint would exclude values the model admits and undercount the cover.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                GlobalCardinality(xs = intArrayOf(0, 1), cover = longArrayOf(1, 2), countVars = intArrayOf(2, 3)),
            ),
            openIntHi = booleanArrayOf(true, false, false, false),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, -1, -1))
        val session = PropagationSession(p)
        val hull = CpToLpRelaxation(p, obj, gccCountHull = true).build(session)
        val bare = CpToLpRelaxation(p, obj, gccCountHull = false).build(session)
        assertEquals(bare.model.m, hull.model.m, "no hull row over an open-sided variable")
        assertEquals(bare.model.n, hull.model.n, "and no selector column")
    }

    @Test
    fun `hull captures the joint count sum the per-count propagator misses`() {
        // x0,x1 ∈ {1,2}; cover {1,2}; count1=var2, count2=var3 ∈ [0,2]. Each count's *possible* upper
        // bound is 2 (both vars can take either value), so the propagator allows count1=count2=2
        // independently — but every var takes exactly one value, so count1+count2 = 2. Maximizing
        // count1+count2 (minimizing −count1−count2) exposes the gap: bare reads −4, the hull's
        // `Σ_v counts = n` linkage reads the true −2.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                GlobalCardinality(xs = intArrayOf(0, 1), cover = longArrayOf(1, 2), countVars = intArrayOf(2, 3)),
            ),
        )
        val maximizeTotalCount = LinearObjective(intCoefficients = longArrayOf(0, 0, -1, -1))
        val session = PropagationSession(p)
        val bare = solveLp(
            CpToLpRelaxation(p, maximizeTotalCount, gccCountHull = false).build(session).model,
        )
        val hull = solveLp(
            CpToLpRelaxation(p, maximizeTotalCount, gccCountHull = true).build(session).model,
        )
        assertEquals(FloatLpStatus.OPTIMAL, hull.status)
        assertEquals(-2.0, hull.objectiveValue, eps, "the two vars contribute exactly 2 to the cover counts")
        assertTrue(hull.objectiveValue > bare.objectiveValue + eps, "the hull beats the per-count domain bound")
    }

}
