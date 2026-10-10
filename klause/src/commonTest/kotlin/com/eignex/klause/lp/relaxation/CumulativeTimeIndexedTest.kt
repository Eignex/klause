package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

class CumulativeTimeIndexedTest {

    private val eps = 1e-6

    /** Sum-of-starts LP bound (`min Σ startᵢ`) with the time-indexed rows on/off. The time-indexed
     *  formulation is exact (integral) for single-machine completion-time objectives, where the
     *  expected-start channel cannot be gamed — unlike loose-domain makespan. */
    private fun sumStartBound(problem: Problem, taskStarts: IntArray, timeIndexed: Boolean): Double {
        val obj = LinearObjective(
            intCoefficients = LongArray(problem.numIntVars) { if (it in taskStarts) 1L else 0L },
        )
        val relaxation = CpToLpRelaxation(problem, obj, cumulativeTimeIndexed = timeIndexed)
            .build(PropagationSession(problem))
        val sol = solveLp(relaxation.model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue
    }

    @Test
    fun `time-indexed is exact for single-machine total completion time`() {
        // 3 unit tasks of length 3 on capacity 1, min Σ startᵢ. Serial starts 0,3,6 ⇒ Σ = 9; the
        // resource rows forbid the all-at-zero point the plain LP allows (Σ = 0). The single-machine
        // time-indexed LP is integral, so it attains exactly 9.
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 20) },
            arrayOf<Factor>(Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), 1)),
        )
        assertEquals(0.0, sumStartBound(p, intArrayOf(0, 1, 2), timeIndexed = false), eps)
        assertEquals(9.0, sumStartBound(p, intArrayOf(0, 1, 2), timeIndexed = true), eps)
    }

    @Test
    fun `disjunctive factor is reformulated too`() {
        // Same single-machine completion-time bound through the Disjunctive surface (cap 1).
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 20) },
            arrayOf<Factor>(Cumulative.unary(intArrayOf(0, 1, 2), longArrayOf(2, 3, 4))),
        )
        // Serial by SPT: starts 0, 2, 5 ⇒ Σ = 7 (the plain LP gives 0).
        assertEquals(0.0, sumStartBound(p, intArrayOf(0, 1, 2), timeIndexed = false), eps)
        assertEquals(7.0, sumStartBound(p, intArrayOf(0, 1, 2), timeIndexed = true), eps)
    }

}
