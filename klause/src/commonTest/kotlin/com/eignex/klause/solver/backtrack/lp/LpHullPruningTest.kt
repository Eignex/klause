package com.eignex.klause.solver.backtrack.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.arithmetic.Product
import com.eignex.klause.solver.factor.table.Table
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Per-hull pruning drops a convex-hull technique that adds no strength to the root LP optimum, keeps one
 * that does, and never changes the optimum (a hull is a sound relaxation either way, dropped only when
 * the root optimum is identical without it).
 */
class LpHullPruningTest {

    /** `result = a·b` (a McCormick hull) plus an unrelated objective variable `x ∈ [5, 9]`; minimizing
     *  `x` cannot be tightened by the product hull, so the hull is ineffective at the root. */
    private fun unrelatedProductProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4, // a, b, result, x
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(-100, 100), IntDomain(5, 9)),
        factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
    )

    @Test
    fun `pruning drops a hull that adds no root strength`() {
        val p = unrelatedProductProblem()
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1)) // minimize x (var 3)
        val engine = LpEngine(
            p,
            obj,
            BacktrackParams(lpPlan = LpPlan(bounding = true, productMcCormick = true, pruneHulls = true)),
            SolveStatsSink(backend = "hull"),
        )
        val original = engine.lpRelaxer
        engine.pruneIneffectiveHulls(Cancellation.Never)
        assertNotSame(original, engine.lpRelaxer, "the McCormick hull adds no x bound, so it must be dropped")
    }

    @Test
    fun `pruning keeps a hull that tightens the root optimum`() {
        // Table over {(0,2), (2,0)}: propagation alone gives x0, x1 ∈ {0,2} (interval [0,2]), so the LP
        // would min x0 + x1 = 0; the table convex hull forces x0 + x1 = 2. Removing it loosens the root
        // optimum, so the hull must be kept (the relaxer is left untouched).
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = intArrayOf(0, 2, 2, 0))),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1)) // minimize x0 + x1
        val engine = LpEngine(
            p,
            obj,
            BacktrackParams(lpPlan = LpPlan(bounding = true, table = true, pruneHulls = true)),
            SolveStatsSink(backend = "hull"),
        )
        val original = engine.lpRelaxer
        engine.pruneIneffectiveHulls(Cancellation.Never)
        assertSame(original, engine.lpRelaxer, "the table hull tightens the root optimum, so it must be kept")
    }

    @Test
    fun `pruning preserves the optimum`() {
        val p = unrelatedProductProblem()
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        fun optimum(prune: Boolean): Double {
            val res = BacktrackSolver(p).minimize(
                obj,
                BacktrackParams(
                    randomSeed = 1L,
                    lpPlan = LpPlan(bounding = true, productMcCormick = true, pruneHulls = prune),
                ),
            )
            return (res as MinimizeResult.Optimal).objectiveValue
        }
        assertEquals(5.0, optimum(prune = false))
        assertTrue(optimum(prune = true) == 5.0, "per-hull pruning must not change the optimum")
    }
}
