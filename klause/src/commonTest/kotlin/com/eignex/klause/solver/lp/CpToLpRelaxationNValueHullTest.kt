package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #435 NValue LP value hull. On a value "triangle" — x0 ∈ {0,1}, x1 ∈ {1,2}, x2 ∈ {0,2} — every pair
 * of domains overlaps but no single value lies in all three (a hole at 1 in x2 breaks the interval
 * Helly property). NValue's propagator only proves `n ≥ 1` (its greedy pairwise-disjoint-domain bound
 * finds no disjoint pair); but the fractional value cover forces `Σ_v y_v ≥ 1.5`, which the hull
 * captures as a strictly tighter LP bound. It is sound — 1.5 stays below the true integer minimum of
 * 2 distinct values, so no solution is excluded.
 */
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
    fun `nvalue hull lower-bounds distinct beyond the greedy propagator bound`() {
        val p = triangle(NValue.Mode.Eq)
        val session = PropagationSession(p)
        // Without the hull the LP only sees the propagator-tightened n domain (≥ 1 here).
        val bare = solveLp(CpToLpRelaxation(p, minimizeN, nValueHull = false).build(session).model)
        // With the hull the fractional value cover proves n ≥ 1.5 — strictly tighter, and sound.
        val hull = solveLp(CpToLpRelaxation(p, minimizeN, nValueHull = true).build(session).model)
        assertEquals(LpStatus.OPTIMAL, hull.status)
        assertEquals(1.5, hull.objectiveValue, eps)
        assertTrue(hull.objectiveValue > bare.objectiveValue + eps, "the hull beats the greedy disjoint bound")
    }

    @Test
    fun `atmost nvalue hull bounds n below by the fractional value cover`() {
        // AtMost means n ≥ distinct; the fractional cover gives n ≥ 1.5.
        val p = triangle(NValue.Mode.AtMost)
        val session = PropagationSession(p)
        val hull = solveLp(CpToLpRelaxation(p, minimizeN, nValueHull = true).build(session).model)
        assertEquals(LpStatus.OPTIMAL, hull.status)
        assertEquals(1.5, hull.objectiveValue, eps)
    }
}
