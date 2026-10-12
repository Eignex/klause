package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.global.NValue
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

class CpToLpRelaxationNValueHullTest {
    private val eps = 1e-7

    private fun triangle(mode: NValue.Mode) = Problem(
        numBoolVars = 0,
        numIntVars = 4, // vars 0,1,2 = xs ; var 3 = n
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(1, 2), IntDomain(0, 2).excludeValue(1), IntDomain(0, 3)),
        factors = arrayOf<Factor>(NValue(n = 3, xs = intArrayOf(0, 1, 2), mode = mode)),
    )

    private val minimizeN = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L)) // minimise n

    @Test
    fun `a variable the model leaves open gets no value hull`() {
        // The selectors enumerate each variable's root box and the channel then pins the column to what
        // was enumerated, so an invented endpoint would exclude values the model admits.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(1, 2), IntDomain(0, 2).excludeValue(1), IntDomain(0, 3)),
            factors = arrayOf<Factor>(NValue(n = 3, xs = intArrayOf(0, 1, 2), mode = NValue.Mode.Eq)),
            openIntHi = booleanArrayOf(false, true, false, false),
        )
        val session = PropagationSession(p)
        val hull = CpToLpRelaxation(p, minimizeN, nValueHull = true).build(session)
        val bare = CpToLpRelaxation(p, minimizeN, nValueHull = false).build(session)
        assertEquals(bare.model.m, hull.model.m, "no hull row over an open-sided variable")
        assertEquals(bare.model.n, hull.model.n, "and no selector column")
    }

    @Test
    fun `nvalue hull lower-bounds distinct beyond the greedy propagator bound`() {
        val p = triangle(NValue.Mode.Eq)
        val session = PropagationSession(p)
        // Without the hull the LP only sees the propagator-tightened n domain (≥ 1 here).
        val bare = solveLp(CpToLpRelaxation(p, minimizeN, nValueHull = false).build(session).model)
        // With the hull the fractional value cover proves n ≥ 1.5 — strictly tighter, and sound.
        val hull = solveLp(CpToLpRelaxation(p, minimizeN, nValueHull = true).build(session).model)
        assertEquals(FloatLpStatus.OPTIMAL, hull.status)
        assertEquals(1.5, hull.objectiveValue, eps)
        assertTrue(hull.objectiveValue > bare.objectiveValue + eps, "the hull beats the greedy disjoint bound")
    }

}
