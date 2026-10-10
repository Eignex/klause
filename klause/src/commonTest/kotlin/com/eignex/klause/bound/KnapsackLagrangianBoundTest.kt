package com.eignex.klause.bound

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertTrue

/** #632: the 0/1 multi-knapsack subgradient Lagrangian bound (one knapsack solved exactly by DP,
 *  the rest dualized). The bound must never exceed the true optimum, for any multipliers. */
class KnapsackLagrangianBoundTest {

    private fun ceil(a: Long, b: Long): Long = if (a % b > 0L) a / b + 1 else a / b

    private fun pb(weights: IntArray, vars: IntArray, op: PbOp, bound: Int): PseudoBoolean = PseudoBoolean(
        LongArray(weights.size) { weights[it].toLong() },
        IntArray(vars.size) { Lit.make(vars[it], true) },
        op,
        bound.toLong(),
    )

    @Test
    fun `exact knapsack bound on a forced cover-style instance`() {
        // 4 bools, minimize -(3 x0 + 2 x1 + 2 x2 + x3) (i.e. maximize a profit) under capacity
        // 4 x0 + 3 x1 + 3 x2 + 2 x3 <= 6, plus a second capacity 2 x0 + 2 x1 + x2 + x3 <= 3.
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(
                pb(intArrayOf(4, 3, 3, 2), intArrayOf(0, 1, 2, 3), PbOp.LE, 6),
                pb(intArrayOf(2, 2, 1, 1), intArrayOf(0, 1, 2, 3), PbOp.LE, 3),
            ),
        )
        val obj = LinearObjective(boolWeights = longArrayOf(-3, -2, -2, -1))
        val lb = KnapsackLagrangianBound(p, obj)
        assertTrue(lb.applicable)
        val r = lb.computeBound(PropagationSession(p), 100.0, LongArray(lb.multiplierCount), 30)
        requireNotNull(r)
        // Brute-force optimum over both capacities: best feasible profit is x0,x3 -> 4+2=6 cap, 2+1=3
        // cap, profit 3+1=4 -> objective -4. The bound is a lower bound, so <= -4.
        assertTrue(!r.prune)
        assertTrue(ceil(r.boundNumerator, r.denominator) <= -4L, "bound ${r.boundNumerator}/${r.denominator} > -4")
    }

}
