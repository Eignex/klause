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
    fun `bound should be a valid lower bound on a weighted all different`() {
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
        assertTrue(ceil(r.boundNumerator, r.denominator) <= 4L, "bound ${r.boundNumerator}/${r.denominator} > 4")
    }

    @Test
    fun `bound should prune an infeasible all different`() {
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
    fun `large assignment costs should not prune a feasible all different`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(1, 1), IntDomain(2, 2)),
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 1, domainSize = 2)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L shl 54, 0L))
        val bound = LagrangianBound(problem, objective)

        val result = assertNotNull(
            bound.computeBound(
                PropagationSession(problem),
                Double.POSITIVE_INFINITY,
                LongArray(bound.multiplierCount),
                1,
            ),
        )

        assertFalse(result.prune)
        assertEquals(1L shl 54, result.boundNumerator / result.denominator)
    }

    @Test
    fun `bound should stay valid over two coupled all different blocks`() {
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
        // The coupling x0 + x3 >= 6 raises the true optimum to 8.
        assertFalse(r.prune)
        assertTrue(ceil(r.boundNumerator, r.denominator) <= 8L, "bound ${r.boundNumerator}/${r.denominator} > 8")
    }

    @Test
    fun `deflected ascent should lift the bound above the undualized base and stay valid`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 5) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6),
                Linear(intArrayOf(2, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 10),
            ),
        )
        val lb = LagrangianBound(problem, LinearObjective(intCoefficients = longArrayOf(1, 1, 1)))
        assertTrue(lb.applicable && lb.multiplierCount == 1)
        // Polyak steps need a target near the optimum 6.
        val incumbent = 7.0

        val oneStep = assertNotNull(lb.computeBound(PropagationSession(problem), incumbent, LongArray(1), 1))
        val converged = assertNotNull(lb.computeBound(PropagationSession(problem), incumbent, LongArray(1), 30))

        val base = ceil(oneStep.boundNumerator, oneStep.denominator)
        val tightened = ceil(converged.boundNumerator, converged.denominator)
        assertTrue(base <= 3L)
        assertTrue(tightened > base)
        assertTrue(tightened <= 6L)
    }

    private fun ceil(a: Long, b: Long): Long {
        val qd = a / b
        return if (a % b > 0L) qd + 1 else qd
    }
}
