package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

class ReifiedLinearBinaryEqLpTest {

    private val eps = 1e-7

    private fun channel(vDomain: IntDomain, bound: Int) = Problem(
        numBoolVars = 1,
        numIntVars = 1,
        intDomains = arrayOf(vDomain),
        factors = arrayOf<Factor>(
            ReifiedLinear(
                auxBoolVar = 0,
                coeffs = intArrayOf(1),
                vars = intArrayOf(0),
                op = LinearOp.EQ,
                bound = bound,
            ),
        ),
    )

    /** LP optimum of `intC·v + boolW·aux` (minimized). */
    private fun lp(p: Problem, intC: Long, boolW: Long): Double {
        val obj = LinearObjective(boolWeights = longArrayOf(boolW), intCoefficients = longArrayOf(intC))
        val sol = solveLp(CpToLpRelaxation(p, obj).build(PropagationSession(p)).model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue
    }

    @Test
    fun `bool-to-01 channel relaxes to the exact v equals aux`() {
        // v ∈ {0,1}, aux ⇔ v==1  ⇒  v = aux, so v − aux ≡ 0 on the whole relaxation.
        val p = channel(IntDomain(0, 1), bound = 1)
        assertEquals(0.0, lp(p, intC = 1, boolW = -1), eps) // minimize v − aux
        assertEquals(0.0, lp(p, intC = -1, boolW = 1), eps) // minimize aux − v
    }

}
