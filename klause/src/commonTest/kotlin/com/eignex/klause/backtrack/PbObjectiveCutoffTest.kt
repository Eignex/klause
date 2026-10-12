package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Optimality requires refuting the incumbent cutoff because the objective has no integer column.
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
