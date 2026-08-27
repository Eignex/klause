package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the LP cost counters cover the same solves the solve counter does.
 *
 * `lpPivotsPerSolve` divides pivots by solves, so the two have to be counted over the same set. A node
 * whose relaxation is infeasible prunes without producing a result, and those are the solves worth
 * costing — a rate that silently omits them reads as a cheap LP that is in fact the expensive one.
 */
class LpInfeasibleSolveCostTest {

    /** A minimisation whose rows conflict below the domain ceiling, so nodes go infeasible while branching. */
    private fun problem(): Pair<Problem, LinearObjective> {
        val n = 8
        val vars = IntArray(n) { it }
        val domains = Array(n) { IntDomain(0, 4) }
        val rows = arrayOf<Factor>(
            Linear(LongArray(n) { 1L }, vars, LinearOp.GE, 22L),
            Linear(LongArray(n) { 1L }, vars, LinearOp.LE, 24L),
            Linear(LongArray(n) { if (it % 2 == 0) 3L else 1L }, vars, LinearOp.LE, 40L),
            Linear(LongArray(n) { if (it % 2 == 0) 1L else 3L }, vars, LinearOp.LE, 40L),
        )
        return Problem(0, n, domains, rows) to LinearObjective(intCoefficients = LongArray(n) { (it % 3 + 1).toLong() })
    }

    @Test
    fun `nodes pruned by an infeasible relaxation still charge their pivots`() {
        val (problem, objective) = problem()

        val result = BacktrackSolver(problem.bake())
            .minimize(objective, BacktrackParams(randomSeed = 7L, lpPlan = LpPlan(bounding = true)))

        val lp = result.stats.lp
        assertTrue(lp.infeasible.sum > 0.0, "the fixture must prune on an infeasible relaxation")
        assertTrue(
            lp.pivots.sum > 0.0,
            "every counted solve must charge its pivots, including the ${lp.infeasible.sum} that pruned",
        )
    }
}
