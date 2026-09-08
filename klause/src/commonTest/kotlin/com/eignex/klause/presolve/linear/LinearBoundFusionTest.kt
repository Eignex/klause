package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Bits
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        val out = p.bake().withPassDelta(Presolve.fuseLinearBounds(p), BakeConfig.NONE)
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
        val out = p.bake().withPassDelta(Presolve.fuseLinearBounds(p), BakeConfig.NONE)
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

    @Test
    fun `fusion reads chain rows on finite and open models while retaining the chain`() {
        for (open in listOf(false, true)) {
            val bounds = if (open) Bits(3).also { bits -> repeat(3, bits::set) } else null
            val model = Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 10L }, bounds, bounds),
                factors = arrayOf(
                    Increasing(intArrayOf(0, 1, 2), strict = false),
                    ge(intArrayOf(1, -1), intArrayOf(0, 1), 0),
                ),
            )

            val delta = Presolve.fuseLinearBounds(model)

            assertContentEquals(intArrayOf(1), delta.droppedIndices)
            assertEquals(1, delta.addedFactors.size)
            assertEquals(LinearOp.EQ, delta.addedFactors.single().linearRows.single().relation)
        }
    }

    @Test
    fun `wide proportional bounds fuse without narrowing their constants`() {
        val huge = BigInteger.ONE shl 100
        val model = problem(
            Linear(intArrayOf(0, 1), arrayOf(huge, huge), LinearOp.LE, huge * 3),
            Linear(intArrayOf(0, 1), arrayOf(huge, huge), LinearOp.GE, huge * 3),
        )

        val delta = Presolve.fuseLinearBounds(model)

        assertEquals(3L, delta.addedFactors.single().linearRows.single().bound)
        assertContentEquals(intArrayOf(0, 1), delta.droppedIndices)
    }

    @Test
    fun `relaxation rows support fusion without authorizing factor removal`() {
        val source = le(intArrayOf(1), intArrayOf(0), 5)
        val relaxation = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Relaxation(source.linearRows)
        }
        val model = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 10) },
            listOf(relaxation, ge(intArrayOf(1), intArrayOf(0), 5)),
        )

        val delta = Presolve.fuseLinearBounds(model)

        assertContentEquals(intArrayOf(1), delta.droppedIndices)
        assertEquals(1, delta.addedFactors.size)
    }

    @Test
    fun `comparison alternatives cannot be used as simultaneous bounds`() {
        val model = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 10) },
            listOf(
                ComparisonClause(intArrayOf(0, 0), arrayOf(LinearOp.LE, LinearOp.GE), longArrayOf(0, 2)),
            ),
        )

        assertTrue(Presolve.fuseLinearBounds(model).isEmpty)
    }

    @Test
    fun `wide and long rows share the same normalized bound group`() {
        val huge = BigInteger.ONE shl 100
        val model = problem(
            Linear(intArrayOf(0, 1), arrayOf(huge, huge), LinearOp.LE, huge * 3),
            ge(intArrayOf(1, 1), intArrayOf(0, 1), 3),
        )

        val delta = Presolve.fuseLinearBounds(model)

        assertEquals(3L, delta.addedFactors.single().linearRows.single().bound)
        assertContentEquals(intArrayOf(0, 1), delta.droppedIndices)
    }

    @Test
    fun `coalescing duplicate long terms can widen before fusion`() {
        val source = le(intArrayOf(1), intArrayOf(0), 0)
        val upper = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(LinearRow.ofInts(intArrayOf(0, 0), longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE), LinearOp.LE, 0)),
            )
        }
        val model = Problem(0, 3, Array(3) { IntDomain(0, 10) }, listOf(upper, ge(intArrayOf(1), intArrayOf(0), 0)))

        val delta = Presolve.fuseLinearBounds(model)

        assertEquals(0L, delta.addedFactors.single().linearRows.single().bound)
        assertContentEquals(intArrayOf(0, 1), delta.droppedIndices)
    }

    @Test
    fun `orienting a minimum long bound preserves an exact contradiction`() {
        val model = problem(
            Linear(longArrayOf(-1), intArrayOf(0), LinearOp.LE, Long.MIN_VALUE),
            Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, Long.MAX_VALUE),
        )

        assertTrue(Presolve.fuseLinearBounds(model).infeasible)
    }
}
