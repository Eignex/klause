package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A pure-Boolean pseudo-Boolean minimize has no objective variable to bound, so optimality is proven only
 * once the incumbent cutoff `Σ w_b·x_b ≤ best − 1` is posted as a refutable pseudo-Boolean constraint and
 * the cutting-planes engine refutes it. These assert a *proven* optimum (not just a best-found), covering
 * both positive and negative objective weights (the cutoff's sign normalization). The cutoff is off by
 * default (net-negative as a uniform default), so these enable it explicitly.
 */
class PbObjectiveCutoffTest {

    private fun atLeast(vars: Int, min: Int): Factor =
        Cardinality(IntArray(vars) { Lit.make(it, true) }, min = min, max = vars)

    @Test
    fun `pure-Boolean minimize proves the optimum with positive weights`() {
        // Minimize 3·x0 + x1 + x2 + x3 subject to at least two true. The two unit-weight vars give 2;
        // x0 (weight 3) stays false.
        val p = Problem(4, 0, emptyArray(), arrayOf(atLeast(4, min = 2)))
        val obj = LinearObjective(boolWeights = longArrayOf(3, 1, 1, 1))
        val r = BacktrackSolver(p.bake()).minimize(obj, BacktrackParams(randomSeed = 1L, pbObjectiveCutoff = true))
        val opt = assertIs<MinimizeResult.Optimal>(r)
        assertEquals(2.0, opt.objective)
    }

    @Test
    fun `pure-Boolean minimize proves the optimum with negative weights`() {
        // Minimize −x0 − x1 − x2 subject to at most one true (max via a ≤1 cardinality). Turning one on
        // reaches −1; the negative weights exercise the cutoff literal-flip.
        val p = Problem(
            3,
            0,
            emptyArray(),
            arrayOf(Cardinality(IntArray(3) { Lit.make(it, true) }, min = 0, max = 1)),
        )
        val obj = LinearObjective(boolWeights = longArrayOf(-1, -1, -1))
        val r = BacktrackSolver(p.bake()).minimize(obj, BacktrackParams(randomSeed = 1L, pbObjectiveCutoff = true))
        val opt = assertIs<MinimizeResult.Optimal>(r)
        assertEquals(-1.0, opt.objective)
    }
}
