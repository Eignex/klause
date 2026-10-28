package com.eignex.klause.bound

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnapsackLagrangianBoundTest {

    private fun ceil(a: Long, b: Long): Long = if (a % b > 0L) a / b + 1 else a / b

    private fun pb(weights: IntArray, vars: IntArray, op: PbOp, bound: Int): PseudoBoolean = PseudoBoolean(
        LongArray(weights.size) { weights[it].toLong() },
        IntArray(vars.size) { Lit.make(vars[it], true) },
        op,
        bound.toLong(),
    )

    @Test
    fun `knapsack bound declines overflowing dynamic programming costs`() {
        val p = Problem(
            2,
            0,
            emptyArray(),
            arrayOf<Factor>(pb(intArrayOf(1, 1), intArrayOf(0, 1), PbOp.LE, 2)),
        )
        val bound = KnapsackLagrangianBound(p, LinearObjective(boolWeights = LongArray(2) { -(1L shl 55) }))

        assertNull(bound.computeBound(PropagationSession(p), Double.POSITIVE_INFINITY, longArrayOf(), 1))
    }

    @Test
    fun `knapsack bound declines an unrepresentable benefit`() {
        val p = Problem(
            1,
            0,
            emptyArray(),
            arrayOf<Factor>(pb(intArrayOf(1), intArrayOf(0), PbOp.LE, 1)),
        )
        val bound = KnapsackLagrangianBound(p, LinearObjective(boolWeights = longArrayOf(-(1L shl 56))))

        assertNull(bound.computeBound(PropagationSession(p), Double.POSITIVE_INFINITY, longArrayOf(), 1))
    }

    @Test
    fun `knapsack bound aggregates repeated linking coefficients`() {
        val p = Problem(
            3,
            0,
            emptyArray(),
            arrayOf<Factor>(
                pb(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), PbOp.LE, 3),
                pb(intArrayOf(1, 1), intArrayOf(0, 0), PbOp.GE, 2),
            ),
        )
        val bound = KnapsackLagrangianBound(p, LinearObjective(boolWeights = longArrayOf(0, 0, 0)))

        val result = assertNotNull(
            bound.computeBound(PropagationSession(p), Double.POSITIVE_INFINITY, longArrayOf(-128), 1),
        )

        assertEquals(0L, result.boundNumerator)
    }

    @Test
    fun `knapsack bound should not exceed the optimum of a two-capacity instance`() {
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
        // The brute-force optimum picks x0 and x3 for objective -4.
        assertTrue(!r.prune)
        assertTrue(ceil(r.boundNumerator, r.denominator) <= -4L, "bound ${r.boundNumerator}/${r.denominator} > -4")
    }
}
