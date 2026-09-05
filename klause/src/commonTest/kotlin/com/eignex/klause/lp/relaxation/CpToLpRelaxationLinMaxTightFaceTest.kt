package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
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
 * Lin_max/min tight face (Anderson big-M). The tight face is a *relaxation* added on top of the
 * envelope, so its LP bound must be **sound** — never cut off the true integer optimum (an over-tight
 * extremum hull is the soundness failure). When the selector is forced integral (a single operand) the
 * tight face binds and is exact. Layout: var 0 = result, 1.. = operands.
 */
class CpToLpRelaxationLinMaxTightFaceTest {

    private val eps = 1e-7

    private fun solve(p: Problem, obj: LinearObjective, tightFace: Boolean): LpSolution {
        val r = CpToLpRelaxation(p, obj, linMaxTightFace = tightFace).build(PropagationSession(p))
        return solveLp(r.model)
    }

    @Test
    fun `tight face keeps the max bound sound`() {
        // result = max(x1∈[1,5], x2∈[2,7]); maximize result ⇒ true integer max = 7 (x2=7).
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 100), IntDomain(1, 5), IntDomain(2, 7)),
            factors = arrayOf<Factor>(ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = true)),
        )
        // maximize result ⇔ minimize −result.
        val sol = solve(p, LinearObjective(intCoefficients = longArrayOf(-1L, 0L, 0L)), tightFace = true)
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        // Sound: the relaxation contains result = 7, so LP max ≥ 7 ⇒ objective (−result) ≤ −7.
        assertTrue(sol.objectiveValue <= -7.0 + eps, "UNSOUND: LP max ${-sol.objectiveValue} below integer optimum 7")
    }

    @Test
    fun `tight face adds selector rows and stays exact for a single operand max`() {
        // result = max(x1∈[1,5]); maximize. Propagation already bounds result to [1,5], so the LP max is
        // 5 either way — the point here is that the tight face actually emits its selector columns and
        // big-M rows (exercised), and the result stays exact (5) and sound.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 100), IntDomain(1, 5)),
            factors = arrayOf<Factor>(ArrayMinMax(result = 0, xs = intArrayOf(1), max = true)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L, 0L))
        val withFace = CpToLpRelaxation(p, obj, linMaxTightFace = true).build(PropagationSession(p))
        val envelope = CpToLpRelaxation(p, obj, linMaxTightFace = false).build(PropagationSession(p))
        assertTrue(withFace.model.n > envelope.model.n, "tight face adds selector columns")
        assertTrue(withFace.model.m > envelope.model.m, "tight face adds rows")
        val sol = solveLp(withFace.model)
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertEquals(-5.0, sol.objectiveValue, eps) // result = x1 max = 5, exact and sound
    }

    @Test
    fun `tight face keeps the min bound sound`() {
        // result = min(x1∈[1,5], x2∈[2,7]); minimize result ⇒ true integer min = 1 (x1=1).
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 100), IntDomain(1, 5), IntDomain(2, 7)),
            factors = arrayOf<Factor>(ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = false)),
        )
        val sol = solve(p, LinearObjective(intCoefficients = longArrayOf(1L, 0L, 0L)), tightFace = true)
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        // Sound: the relaxation contains result = 1, so LP min ≤ 1.
        assertTrue(sol.objectiveValue <= 1.0 + eps, "UNSOUND: LP min ${sol.objectiveValue} above integer optimum 1")
    }

    /** `result = max(x1, x2)` with the sides [openHi] marks as ones the finite lane invented. */
    private fun maxProblem(openHi: BooleanArray): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 100), IntDomain(1, 5), IntDomain(2, 7)),
        factors = arrayOf<Factor>(ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = true)),
        openIntHi = openHi,
    )

    @Test
    fun `an operand the model leaves open loses only its own big-M row`() {
        // Each M spans the result's root box and the operand's, so an invented endpoint on either makes
        // the row a search restriction. x2's selector stays in Σ z = 1 — dropping it would force the
        // extremum onto x1, which the factor never says.
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L, 0L, 0L))
        val shut = maxProblem(BooleanArray(3))
        val closed = CpToLpRelaxation(shut, obj, linMaxTightFace = true).build(PropagationSession(shut))
        val open = maxProblem(booleanArrayOf(false, false, true))
        val opened = CpToLpRelaxation(open, obj, linMaxTightFace = true).build(PropagationSession(open))
        assertEquals(closed.model.m - 1, opened.model.m, "one big-M row declined")
        assertEquals(closed.model.n, opened.model.n, "both selectors stay in the one-hot row")
    }

    @Test
    fun `a result the model leaves open drops the whole tight face`() {
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L, 0L, 0L))
        val open = maxProblem(booleanArrayOf(true, false, false))
        val withFace = CpToLpRelaxation(open, obj, linMaxTightFace = true).build(PropagationSession(open))
        val envelope = CpToLpRelaxation(open, obj, linMaxTightFace = false).build(PropagationSession(open))
        assertEquals(envelope.model.m, withFace.model.m, "no M bounds the gap, so no face row holds")
        assertEquals(envelope.model.n, withFace.model.n, "and no selector column is allocated")
    }
}
