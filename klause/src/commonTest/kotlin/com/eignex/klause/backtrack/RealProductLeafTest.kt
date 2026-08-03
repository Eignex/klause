package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * The [RealProduct] leaf verdict (issue #1232, Phase 7): `result = intOperand · realOperand` over an
 * integer CP variable and LP-only continuous columns. At a leaf the integer operand is fixed, so the
 * product is the exact linear equality `result = k · realOperand` and the residual real LP decides
 * feasibility exactly — feasible ⇒ SAT, no feasible real completion ⇒ UNSAT, checked across every value
 * the integer operand can take.
 */
class RealProductLeafTest {

    // Reals: index 0 = realOperand, index 1 = result. The [RealProduct] and any extra rows are the factors.
    private fun problem(intDoms: Array<IntDomain>, opLo: Double, opHi: Double, vararg factors: Factor) = Problem(
        numBoolVars = 0,
        numIntVars = intDoms.size,
        intDomains = intDoms,
        factors = arrayOf(*factors),
        numRealVars = 2,
        realLower = doubleArrayOf(opLo, 0.0),
        realUpper = doubleArrayOf(opHi, 100.0),
    )

    /** The LP-only equality `result = value`, fixing the continuous result column. */
    private fun resultEquals(value: Long): Linear =
        Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(1), LinearOp.EQ, value)

    @Test
    fun `fixed integer operand yields an exact product certified SAT`() {
        // x = 2, operand r in [0,5], result w = 2·r; forcing w = 6 needs r = 3, feasible.
        val p = problem(arrayOf(IntDomain(2, 2)), 0.0, 5.0, RealProduct(0, 0, 1, 0.0, 5.0), resultEquals(6))
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `exact product with no feasible real completion is UNSAT`() {
        // x = 2, w = 2·r; forcing w = 12 needs r = 6, outside r in [0,5] — exact Farkas certifies UNSAT.
        val p = problem(arrayOf(IntDomain(2, 2)), 0.0, 5.0, RealProduct(0, 0, 1, 0.0, 5.0), resultEquals(12))
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `integer operand is enumerated and every leaf product is checked`() {
        // x in [1,3], operand pinned to r = 2, so w = 2·x at each leaf; w = 5 has no integer x — UNSAT.
        val p = problem(arrayOf(IntDomain(1, 3)), 2.0, 2.0, RealProduct(0, 0, 1, 2.0, 2.0), resultEquals(5))
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `feasible bilinear product across a branch is SAT`() {
        // x in [1,3], r in [0,5], w = x·r; forcing w = 9 is met at the x = 3 leaf with r = 3 (exact).
        val p = problem(arrayOf(IntDomain(1, 3)), 0.0, 5.0, RealProduct(0, 0, 1, 0.0, 5.0), resultEquals(9))
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }
}
