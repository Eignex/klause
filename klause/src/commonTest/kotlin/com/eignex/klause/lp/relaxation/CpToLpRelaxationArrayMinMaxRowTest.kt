package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.lp.LpSolution
import com.eignex.klause.lp.LpStatus
import com.eignex.klause.lp.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LP relaxation rows for `ArrayMinMax` (`result = max(xs)` / `min(xs)`). The extremum is the
 * envelope of its operands, so the rows bound an objective that minimises a maximum (makespan)
 * or maximises a minimum — exactly where the bare per-variable domains say nothing.
 */
class CpToLpRelaxationArrayMinMaxRowTest {

    private val eps = 1e-9

    private fun solve(problem: Problem, objective: LinearObjective?): Pair<LpSolution, LpRelaxation> {
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        return solveLp(relaxation.model) to relaxation
    }

    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    @Test
    fun `max envelope lower-bounds a minimised result`() {
        // result = max(x0, x1, x2), x0 in [3,5], x1 in [1,4], x2 in [0,2], result in [0,10].
        // Minimising result: result >= every operand, and the tightest operand floor is x0 >= 3,
        // so result >= 3. Without the rows result would float to its own domain floor 0.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(3, 5), IntDomain(1, 4), IntDomain(0, 2), IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = true),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L)))

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(3.0, sol.objectiveValue, eps)
        assertEquals(3.0, sol.primal(intCol(r, 3)), eps)
    }

    @Test
    fun `min envelope upper-bounds a maximised result`() {
        // result = min(x0, x1, x2), same domains. Maximising result: result <= every operand,
        // and the tightest operand ceiling is x2 <= 2, so result <= 2.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(3, 5), IntDomain(1, 4), IntDomain(0, 2), IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = false),
            ),
        )
        // maximise result <=> minimise -result
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, -1L)))

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(-2.0, sol.objectiveValue, eps)
        assertEquals(2.0, sol.primal(intCol(r, 3)), eps)
    }
}
