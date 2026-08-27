package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a search actually pays for its node LPs.
 *
 * A child node differs from its parent in column bounds alone, so the parent's factorization is still
 * valid there. Rebuilding it per node is the expensive half of a node solve, and the counter that shows
 * whether it happens is factorizations against solves: one apiece means every node refactorized.
 */
class LpNodeReuseTest {

    /** An integer-linear minimisation wide enough to branch, so the LP runs at more than one node. */
    private fun problem(): Pair<Problem, LinearObjective> {
        val rng = Random(4242)
        val n = 14
        val domains = Array(n) { IntDomain(0, 6) }
        val vars = IntArray(n) { it }
        val rows = ArrayList<Factor>()
        repeat(7) {
            rows += Linear(
                LongArray(n) { rng.nextLong(1L, 5L) },
                vars,
                LinearOp.GE,
                rng.nextLong(12L, 26L),
            )
        }
        val objective = LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(1L, 6L) })
        return Problem(0, n, domains, rows.toTypedArray()) to objective
    }

    @Test
    fun `node LP solves reuse a factorization instead of rebuilding one each time`() {
        val (problem, objective) = problem()

        // The reuse ratio is visible within the first handful of nodes, so the counters do not need a
        // solve to proven optimality; a node budget keeps the fixture off the full 1000-node tree.
        val result = BacktrackSolver(problem.bake()).minimize(
            objective,
            BacktrackParams(randomSeed = 11L, lpPlan = LpPlan(bounding = true), nodeBudget = NodeBudget(20)),
        )

        val lp = result.stats.lp
        assertTrue(lp.solves.sum > 1.0, "the search must solve more than one node LP, saw ${lp.solves.sum}")
        assertTrue(
            lp.refactorizations.sum < lp.solves.sum,
            "every node rebuilt its factorization: ${lp.refactorizations.sum} for ${lp.solves.sum} solves",
        )
    }
}
