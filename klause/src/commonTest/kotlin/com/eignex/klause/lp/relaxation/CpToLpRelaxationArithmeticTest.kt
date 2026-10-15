package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Product
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

class CpToLpRelaxationArithmeticTest {
    private val eps = 1e-7

    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    private fun envelopeProblem(max: Boolean) = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(3, 5), IntDomain(1, 4), IntDomain(0, 2), IntDomain(0, 10)),
        factors = arrayOf<Factor>(ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = max)),
    )

    @Test
    fun `max envelope should lower-bound a minimised result`() {
        val p = envelopeProblem(max = true)
        val r = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L)))
            .build(PropagationSession(p))
        val sol = solveLp(r.model)

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(3.0, sol.primal(intCol(r, 3)), eps)
    }

    @Test
    fun `min envelope should upper-bound a maximised result`() {
        val p = envelopeProblem(max = false)
        val r = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, -1L)))
            .build(PropagationSession(p))
        val sol = solveLp(r.model)

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.primal(intCol(r, 3)), eps)
    }

    private fun maxProblem(openHi: BooleanArray? = null): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 100), IntDomain(1, 5), IntDomain(2, 7)),
        factors = arrayOf<Factor>(ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = true)),
        openIntHi = openHi,
    )

    private val maximizeResult = LinearObjective(intCoefficients = longArrayOf(-1L, 0L, 0L))

    @Test
    fun `tight face should add selector rows and stay exact for a single operand max`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 100), IntDomain(1, 5)),
            factors = arrayOf<Factor>(ArrayMinMax(result = 0, xs = intArrayOf(1), max = true)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L, 0L))
        val withFace = CpToLpRelaxation(p, obj, linMaxTightFace = true).build(PropagationSession(p))
        val envelope = CpToLpRelaxation(p, obj, linMaxTightFace = false).build(PropagationSession(p))
        assertTrue(withFace.model.n > envelope.model.n)
        assertTrue(withFace.model.m > envelope.model.m)
        val sol = solveLp(withFace.model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(-5.0, sol.objectiveValue, eps)
    }

    @Test
    fun `tight face should drop only the big-M row of an open operand`() {
        val shut = maxProblem()
        val closed = CpToLpRelaxation(shut, maximizeResult, linMaxTightFace = true).build(PropagationSession(shut))
        val open = maxProblem(booleanArrayOf(false, false, true))
        val opened = CpToLpRelaxation(open, maximizeResult, linMaxTightFace = true).build(PropagationSession(open))
        assertEquals(closed.model.m - 1, opened.model.m)
        // Dropping x2's selector would force the extremum onto x1.
        assertEquals(closed.model.n, opened.model.n)
    }

    @Test
    fun `tight face should be dropped entirely when the result is open`() {
        val open = maxProblem(booleanArrayOf(true, false, false))
        val withFace = CpToLpRelaxation(open, maximizeResult, linMaxTightFace = true).build(PropagationSession(open))
        val envelope = CpToLpRelaxation(open, maximizeResult, linMaxTightFace = false).build(PropagationSession(open))
        assertEquals(envelope.model.m, withFace.model.m)
        assertEquals(envelope.model.n, withFace.model.n)
    }

    private fun productProblem(a: IntDomain, b: IntDomain, openIntHi: BooleanArray? = null): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(a, b, IntDomain(-100, 100)),
        factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        openIntHi = openIntHi,
    )

    private fun mcCormick(p: Problem, obj: LinearObjective, on: Boolean = true) =
        CpToLpRelaxation(p, obj, productMcCormick = on).build(PropagationSession(p))

    @Test
    fun `mccormick envelope should keep the product bounds sound`() {
        val p = productProblem(IntDomain(1, 3), IntDomain(2, 4))
        val min = solveLp(mcCormick(p, LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L))).model)
        assertEquals(FloatLpStatus.OPTIMAL, min.status)
        assertTrue(min.objectiveValue <= 2.0 + eps, "UNSOUND: LP min ${min.objectiveValue} above bilinear min 2")
        val max = solveLp(mcCormick(p, LinearObjective(intCoefficients = longArrayOf(0L, 0L, -1L))).model)
        assertEquals(FloatLpStatus.OPTIMAL, max.status)
        assertTrue(max.objectiveValue <= -12.0 + eps, "UNSOUND: LP max ${-max.objectiveValue} below bilinear max 12")
    }

    @Test
    fun `mccormick envelope should stay sound for a square`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 4), IntDomain(-100, 100)),
            factors = arrayOf<Factor>(Product(a = 0, b = 0, result = 1)),
        )
        val sol = solveLp(mcCormick(p, LinearObjective(intCoefficients = longArrayOf(0L, 1L))).model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertTrue(sol.objectiveValue <= 1.0 + eps, "UNSOUND: square LP min ${sol.objectiveValue} above a² min 1")
    }

    @Test
    fun `mccormick envelope should drop the envelopes over an open operand side`() {
        val p = productProblem(IntDomain(1, 3), IntDomain(2, 4), openIntHi = booleanArrayOf(true, false, false))
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L))
        assertEquals(mcCormick(p, obj, on = false).model.m + 2, mcCormick(p, obj).model.m)
    }

    @Test
    fun `mccormick envelope should decline a constant past the Long range instead of wrapping`() {
        val wide = 4_000_000_000L
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, wide), IntDomain(0, wide), IntDomain(0, 100)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L))
        // RootDomains reads endpoints only, so a span this wide never enters a per-value fixpoint.
        val withMc = CpToLpRelaxation(p, obj, productMcCormick = true).build(RootDomains(p))
        val without = CpToLpRelaxation(p, obj, productMcCormick = false).build(RootDomains(p))
        assertEquals(without.model.m + 3, withMc.model.m)
        val sol = solveLp(withMc.model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertTrue(sol.objectiveValue <= eps, "UNSOUND: LP min ${sol.objectiveValue} above the product's 0")
    }
}
