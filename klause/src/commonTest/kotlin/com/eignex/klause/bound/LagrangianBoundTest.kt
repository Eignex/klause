package com.eignex.klause.bound

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LagrangianBoundTest {

    @Test
    fun `bound is a valid lower bound on a weighted all-different`() {
        // min 1·x0 + 2·x1 + 3·x2, AllDifferent over [0,4]. Cheapest distinct assignment by the
        // assignment problem: the largest weight takes value 0, etc. -> exact via Hungarian.
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 4) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 5)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 2, 3))
        val lb = LagrangianBound(p, obj)
        assertTrue(lb.applicable)
        val r = assertNotNull(
            lb.computeBound(PropagationSession(p), Double.POSITIVE_INFINITY, LongArray(lb.multiplierCount), 1),
        )
        // True optimum: x2=0,x1=1,x0=2 -> 2 + 2 + 0 = 4. The bound must not exceed it.
        assertTrue(ceil(r.boundNumerator, r.denominator) <= 4L, "bound ${r.boundNumerator}/${r.denominator} > 4")
    }

    @Test
    fun `infeasible all-different is pruned`() {
        // 3 distinct variables but only 2 values -> no assignment.
        val p = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val lb = LagrangianBound(p, obj)
        val r = assertNotNull(
            lb.computeBound(PropagationSession(p), Double.POSITIVE_INFINITY, LongArray(lb.multiplierCount), 1),
        )
        assertTrue(r.prune)
    }

    @Test
    fun `two coupled all-different blocks give a valid bound`() {
        // Two disjoint AllDifferents (x0..x2 and x3..x5) over [0,4], coupled by x0 + x3 >= 6.
        // Each block is solved as its own assignment; the coupling is priced via a multiplier.
        val p = Problem(
            0,
            6,
            Array(6) { IntDomain(0, 4) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 5),
                AllDifferent(intArrayOf(3, 4, 5), domainMin = 0, domainSize = 5),
                Linear(intArrayOf(1, 1), intArrayOf(0, 3), LinearOp.GE, 6),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1, 1, 1))
        val lb = LagrangianBound(p, obj)
        assertTrue(lb.applicable)
        assertEquals(1, lb.multiplierCount)
        val r = assertNotNull(lb.computeBound(PropagationSession(p), 100.0, LongArray(lb.multiplierCount), 20))
        // True optimum: unconstrained each block is 0+1+2 = 3, but x0+x3>=6 forces one anchor up — the
        // cheapest is {2,0,1} and {4,0,1} (2+4=6), total 8. The bound is a lower bound, so ≤ 8.
        assertFalse(r.prune)
        assertTrue(ceil(r.boundNumerator, r.denominator) <= 8L, "bound ${r.boundNumerator}/${r.denominator} > 8")
    }

    private fun ceil(a: Long, b: Long): Long {
        val qd = a / b
        return if (a % b > 0L) qd + 1 else qd
    }
}
