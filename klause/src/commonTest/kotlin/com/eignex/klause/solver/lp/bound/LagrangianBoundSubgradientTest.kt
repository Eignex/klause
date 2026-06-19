package com.eignex.klause.solver.lp.bound

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.linear.Linear
import com.eignex.klause.solver.factor.linear.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertTrue

/** #23: deflected (conjugate) subgradient stabilization of the Lagrangian dual ascent. */
class LagrangianBoundSubgradientTest {

    // min Σ x_i over AllDifferent(3) in [0,5] with the binding linking constraint 2·x0 + x1 + x2 >= 10.
    // True optimum: x0=5, x1=0, x2=1 -> 6. The base bound (no dualization) is only 0+1+2 = 3, so the
    // subgradient must lift it above 3 by pricing the linking constraint.
    private val problem = Problem(
        0,
        3,
        Array(3) { IntDomain(0, 5) },
        arrayOf<Factor>(
            AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6),
            Linear(intArrayOf(2, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 10),
        ),
    )
    private val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))

    private fun ceil(n: Long, d: Long) = if (n % d > 0L) n / d + 1 else n / d

    @Test
    fun `deflected ascent lifts the bound above the undualized base and stays valid`() {
        val lb = LagrangianBound(problem, obj)
        assertTrue(lb.applicable && lb.multiplierCount == 1)
        // Incumbent just above the true optimum (6): Polyak needs a target near the optimum to size
        // its steps; 7 never prunes (the dual bound stays below it) but drives the ascent.
        val incumbent = 7.0

        val oneStep = lb.computeBound(PropagationSession(problem), incumbent, LongArray(1), 1)!!
        val converged = lb.computeBound(PropagationSession(problem), incumbent, LongArray(1), 30)!!

        val base = ceil(oneStep.boundNumerator, oneStep.denominator)
        val tightened = ceil(converged.boundNumerator, converged.denominator)
        assertTrue(base <= 3L, "undualized base bound should be the assignment min 3, got $base")
        assertTrue(tightened > base, "subgradient did not improve the bound: $tightened <= $base")
        assertTrue(tightened <= 6L, "bound $tightened exceeds the true optimum 6 — unsound")
    }
}
