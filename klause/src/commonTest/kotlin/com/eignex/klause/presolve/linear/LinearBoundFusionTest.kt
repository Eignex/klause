package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-direction linear bound fusion ([Presolve.fuseLinearBounds]). Each test drives the pass over a
 * small integer problem and checks the meeting bounds collapse into an equality, a crossing pair reports
 * infeasibility, and unrelated shapes are left untouched.
 */
class LinearBoundFusionTest {

    private fun le(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.LE, bound)
    private fun ge(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.GE, bound)

    private fun problem(vararg factors: Linear) = Problem(0, 3, Array(3) { IntDomain(0, 10) }, factors.toList())

    private fun equalities(p: Problem) = p.factors.filterIsInstance<Linear>().filter { it.op == LinearOp.EQ }

    @Test
    fun `an upper and lower bound that meet fuse into an equality`() {
        // x + y ≤ 5 and x + y ≥ 5 together are x + y = 5.
        val p = problem(
            le(intArrayOf(1, 1), intArrayOf(0, 1), 5),
            ge(intArrayOf(1, 1), intArrayOf(0, 1), 5),
        )
        val out = p.withPassDelta(Presolve.fuseLinearBounds(p), BakeConfig.NONE)
        val eqs = equalities(out)
        assertEquals(1, eqs.size, "the pair collapses to one equality")
        assertEquals(5L, checkNotNull(eqs.single().integerConstants).bound)
        assertEquals(setOf(0, 1), eqs.single().vars.toSet())
        assertTrue(out.factors.none { it is Linear && it.op == LinearOp.LE }, "the inequalities are dropped")
    }

    @Test
    fun `bounds meet across a shared multiple`() {
        // 2x + 2y ≤ 10 (i.e. x + y ≤ 5) and x + y ≥ 5 meet at x + y = 5 after GCD reduction.
        val p = problem(
            le(intArrayOf(2, 2), intArrayOf(0, 1), 10),
            ge(intArrayOf(1, 1), intArrayOf(0, 1), 5),
        )
        val out = p.withPassDelta(Presolve.fuseLinearBounds(p), BakeConfig.NONE)
        assertEquals(1, equalities(out).size, "proportional rows share a vector and fuse")
    }

    @Test
    fun `crossing bounds prove infeasibility`() {
        // x + y ≤ 3 and x + y ≥ 5 cannot both hold.
        val p = problem(
            le(intArrayOf(1, 1), intArrayOf(0, 1), 3),
            ge(intArrayOf(1, 1), intArrayOf(0, 1), 5),
        )
        assertTrue(Presolve.fuseLinearBounds(p).infeasible, "the crossing pair is infeasible")
    }

    @Test
    fun `a proper interval is left untouched`() {
        // 3 ≤ x + y ≤ 5 is a genuine range, not an equality.
        val p = problem(
            le(intArrayOf(1, 1), intArrayOf(0, 1), 5),
            ge(intArrayOf(1, 1), intArrayOf(0, 1), 3),
        )
        assertTrue(Presolve.fuseLinearBounds(p).isEmpty, "a strict interval stays as two inequalities")
    }

    @Test
    fun `a lone upper bound is left untouched`() {
        val p = problem(le(intArrayOf(1, 1), intArrayOf(0, 1), 5))
        assertTrue(Presolve.fuseLinearBounds(p).isEmpty, "one direction alone offers nothing to fuse")
    }

    @Test
    fun `an existing equality is not duplicated`() {
        // The equality already pins the vector; the redundant inequalities are left for subsumption.
        val p = problem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 5),
            le(intArrayOf(1, 1), intArrayOf(0, 1), 5),
            ge(intArrayOf(1, 1), intArrayOf(0, 1), 5),
        )
        assertTrue(Presolve.fuseLinearBounds(p).isEmpty, "no second equality is minted")
    }

    @Test
    fun `an equality contradicted by a tighter inequality is infeasible`() {
        // x + y = 5 with x + y ≤ 3 cannot hold.
        val p = problem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 5),
            le(intArrayOf(1, 1), intArrayOf(0, 1), 3),
        )
        assertTrue(Presolve.fuseLinearBounds(p).infeasible, "the equality and a tighter cap contradict")
    }
}
