package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exact LP hull for `aux ⇔ (v == bound)` when `v`'s declared domain is binary — the tight replacement
 * for the big-M reification. The reified value is an affine function of the indicator, so the objective
 * `v − k·aux` is *constant* on the LP feasible region; a big-M relaxation would leave it a slack range.
 * Layout: bool var 0 = aux, int var 0 = v.
 */
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
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        return sol.objectiveValue
    }

    @Test
    fun `bool-to-01 channel relaxes to the exact v equals aux`() {
        // v ∈ {0,1}, aux ⇔ v==1  ⇒  v = aux, so v − aux ≡ 0 on the whole relaxation.
        val p = channel(IntDomain(0, 1), bound = 1)
        assertEquals(0.0, lp(p, intC = 1, boolW = -1), eps) // minimize v − aux
        assertEquals(0.0, lp(p, intC = -1, boolW = 1), eps) // minimize aux − v
    }

    @Test
    fun `pm1 channel relaxes to the exact v equals 2 aux minus 1`() {
        // v ∈ {-1,1}, aux ⇔ v==1  ⇒  v = 2·aux − 1, so v − 2·aux ≡ −1 on the whole relaxation.
        val p = channel(IntDomain(-1, 1).excludeValue(0), bound = 1)
        assertEquals(-1.0, lp(p, intC = 1, boolW = -2), eps) // minimize v − 2·aux
        assertEquals(1.0, lp(p, intC = -1, boolW = 2), eps) // minimize 2·aux − v
    }

    @Test
    fun `a column too wide to enumerate takes the big-M rows instead of failing`() {
        // The exact hull applies only to a two-valued column, and the check for that used to walk the
        // domain. A column spanning 2^31 values has no span to walk, so asking cost a thrown
        // IllegalStateException out of the relaxation build — a crash on a model that is merely wide.
        val problem = channel(IntDomain(-2147483647, 0), bound = 0)

        val relaxation = CpToLpRelaxation(problem, null).build(PropagationSession(problem))

        assertEquals(LpVerdict.OPTIMAL, solveLp(relaxation.model).status)
    }
}
