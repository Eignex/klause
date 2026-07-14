package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.table.Table
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.rootLpObjective
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Per-hull pruning drops a convex-hull technique that adds no strength to the root LP optimum, keeps one
 * that does, and never changes the optimum (a hull is a sound relaxation either way, dropped only when
 * the root optimum is identical without it).
 */
class LpEngineHullPruningTest {

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
            LpParams(lpPlan = LpPlan(bounding = true, productMcCormick = true, pruneHulls = true)),
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
            factors = arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 2, 2, 0))),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1)) // minimize x0 + x1
        val engine = LpEngine(
            p,
            obj,
            LpParams(lpPlan = LpPlan(bounding = true, table = true, pruneHulls = true)),
            SolveStatsSink(backend = "hull"),
        )
        val original = engine.lpRelaxer
        engine.pruneIneffectiveHulls(Cancellation.Never)
        assertSame(original, engine.lpRelaxer, "the table hull tightens the root optimum, so it must be kept")
    }

    @Test
    fun `pruning drops only the ineffective hull of a family and keeps the effective one`() {
        // Two Table hulls of the same family: A on the objective vars (x0, x1) forces x0 + x1 = 2 —
        // effective; B on unrelated vars (x2, x3) adds no objective strength — ineffective. Whole-family
        // pruning is all-or-nothing on `table` and would keep both (dropping the family loosens the
        // optimum); per-factor pruning drops B and keeps A, leaving the root optimum at 2.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 2, 2, 0)),
                Table(xs = intArrayOf(2, 3), tuples = longArrayOf(0, 2, 2, 0)),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 0, 0)) // minimize x0 + x1
        val engine = LpEngine(
            p,
            obj,
            LpParams(lpPlan = LpPlan(bounding = true, table = true, pruneHulls = true)),
            SolveStatsSink(backend = "hull"),
        )
        val original = engine.lpRelaxer
        engine.pruneIneffectiveHulls(Cancellation.Never)
        assertNotSame(original, engine.lpRelaxer, "the unrelated table hull must be dropped per-factor")
        assertEquals(
            2.0,
            engine.rootLpObjective(engine.lpRelaxer!!, Cancellation.Never),
            1e-6,
            "the objective table hull must be kept, so the root optimum stays 2",
        )
    }
}
