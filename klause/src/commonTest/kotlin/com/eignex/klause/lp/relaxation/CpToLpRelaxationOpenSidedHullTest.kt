package com.eignex.klause.lp.relaxation

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The root relaxation over a model with open integer sides. [RootDomains] is where openness is honoured:
 * a column the model does not bound above enters the LP with no upper bound at all, so a hull row built
 * on the box endpoint the finite lane invented turns the relaxation into a *restriction* — it refutes
 * assignments the model admits, and every bound and Farkas ray read off it is then void.
 *
 * Each case below fixes a value the model genuinely reaches and asserts the relaxation still reaches it.
 */
class CpToLpRelaxationOpenSidedHullTest {

    private val eps = 1e-6

    @Test
    fun `a product past the invented operand endpoint stays reachable`() {
        // a ≥ 1 open above with the finite lane's box stopping at 3, b ∈ [2,4], result = a·b ∈ [0,100].
        // The model reaches result = 100 (a = 25, b = 4). The two envelopes over `aH` together imply
        // a ≤ 3 and would cap result at 12.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(2, 4), IntDomain(0, 100)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
            openIntHi = booleanArrayOf(true, false, false),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L, -1L)) // maximize result
        val sol = solveLp(CpToLpRelaxation(p, obj, productMcCormick = true).build(RootDomains(p)).model)
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertTrue(-sol.objectiveValue >= 100.0 - eps, "UNSOUND: LP max ${-sol.objectiveValue} below result = 100")
    }

    @Test
    fun `an element position past the invented index endpoint stays reachable`() {
        // idx ≥ 0 open above, with the finite lane's box stopping at 1 — narrower than the array. The
        // selector hull would enumerate positions 0 and 1 only and pin result to {7, 3}, refuting
        // arr[2] = 9, which the model reaches.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 20)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(7, 3, 9, 5), arrIsVars = false, indexOffset = 0),
            ),
            openIntHi = booleanArrayOf(true, false),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, -1L)) // maximize result
        val sol = solveLp(CpToLpRelaxation(p, obj, elementHull = true).build(RootDomains(p)).model)
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertTrue(-sol.objectiveValue >= 9.0 - eps, "UNSOUND: LP max ${-sol.objectiveValue} below arr[2] = 9")
    }

    @Test
    fun `a table tuple past the invented column endpoint stays reachable`() {
        // x1 ≥ 0 open above with the box stopping at 2, so the tuple (0, 5) falls outside it. Screening
        // the tuples on that box would leave x0 ∈ {2, 4} and refute x0 = 0, which the table allows.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 2)),
            factors = arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 5, 2, 2, 4, 0))),
            openIntHi = booleanArrayOf(false, true),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 0L)) // minimize x0
        val sol = solveLp(CpToLpRelaxation(p, obj, tableHull = true).build(RootDomains(p)).model)
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertTrue(sol.objectiveValue <= eps, "UNSOUND: LP min ${sol.objectiveValue} above the tuple's x0 = 0")
    }

    @Test
    fun `an open-sided model keeps its optimum with the hulls enabled`() {
        // Vars: 0 = idx (open above), 1 = result, 2 = a (open above), 3 = a². Minimum is arr[1] = 3 plus
        // 1² = 1. The declines only remove rows, so the bounded search must land where it lands without
        // the LP at all.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 20), IntDomain(1, 3), IntDomain(-100, 100)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(7, 3, 9, 5), arrIsVars = false, indexOffset = 0),
                Product(a = 2, b = 2, result = 3),
            ),
            openIntHi = booleanArrayOf(true, false, true, false),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 1L, 0L, 1L))
        val base = BacktrackParams(randomSeed = 1L)
        val noLp = BacktrackSolver(p.bake()).minimize(obj, base)
        val withLp = BacktrackSolver(p.bake()).minimize(
            obj,
            base.copy(lpPlan = LpPlan(bounding = true, element = true, productMcCormick = true)),
        )
        assertTrue(noLp is MinimizeResult.Optimal, "baseline should solve, got $noLp")
        assertTrue(withLp is MinimizeResult.Optimal, "LP solve should be optimal, got $withLp")
        assertEquals(4.0, noLp.objective, eps, "arr[1] = 3 plus the square's minimum 1")
        assertEquals(noLp.objective, withLp.objective, eps, "the hulls must not change the optimum")
    }
}
