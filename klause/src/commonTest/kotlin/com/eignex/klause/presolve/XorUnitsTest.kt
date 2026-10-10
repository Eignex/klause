package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XorUnitsTest {

    private fun units(problem: Problem): List<Int> =
        problem.factors.filterIsInstance<Clause>().filter { it.literals.size == 1 }.map { it.literals[0] }

    private fun derived(problem: Problem): Problem {
        val baked = problem.bake()
        return baked.withPassDelta(Presolve.deriveXorUnits(baked), BakeConfig.NONE)
    }

    @Test
    fun `elimination across rows derives a unit no single xor shows`() {
        // x0 ⊕ x1 = 0 and x0 ⊕ x1 ⊕ x2 = 1: neither row is a unit, but their sum forces x2 = true.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true)), targetParity = 0),
                Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), targetParity = 1),
            ),
        )
        val out = derived(problem)
        assertEquals(listOf(Lit.make(2, true)), units(out), "the cross-row residue forces x2 = true")
    }

    @Test
    fun `a contradictory system refutes the model`() {
        // x0 = true and x0 = false reduce to 0 = 1.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Xor(intArrayOf(Lit.make(0, true)), targetParity = 1),
                Xor(intArrayOf(Lit.make(0, true)), targetParity = 0),
            ),
        )

        assertTrue(Presolve.deriveXorUnits(problem.bake()).infeasible)
    }

    @Test
    fun `an equality's parity forces its 0-1 column whatever form its coefficients take`() {
        val wideOdd = parseBigInt("18446744073709551617")
        val wideEven = parseBigInt("36893488147419103232")
        val cases = listOf(
            // -x - 2y = -1: negative coefficients and bound.
            listOf(IntDomain(0, 1), IntDomain(-10, 10)) to
                Linear(longArrayOf(-1L, -2L), intArrayOf(0, 1), LinearOp.EQ, -1L),
            // (2^64 + 1)x + 2^65·y = 1: a row past 64 bits.
            listOf(IntDomain(0, 1), IntDomain(-10, 10)) to
                Linear(intArrayOf(0, 1), arrayOf(wideOdd, wideEven), LinearOp.EQ, BIG_ONE),
            // f + x + 2y = 1 with f fixed at 1, so x is even.
            listOf(IntDomain(0, 1), IntDomain(-10, 10), IntDomain(1, 1)) to
                Linear(longArrayOf(1L, 2L, 1L), intArrayOf(0, 1, 2), LinearOp.EQ, 1L),
        )
        for ((index, case) in cases.withIndex()) {
            val (domains, row) = case
            val problem = Problem(0, domains.size, domains.toTypedArray(), listOf<Factor>(row))
            val expected = if (index == 2) 0L else 1L

            val bounds = assertNotNull(XorUnits.deriveXorUnits(problem).bounds, "case $index")

            assertEquals(expected to expected, bounds.lower(0) to bounds.upper(0), "case $index")
        }
    }

    /** `rows` over 0/1 columns `x0..`, each row `Σ xᵢ + 2·y = rhs` with its own integer `y` open both ways. */
    private fun parityRows(numBinaries: Int, vararg rows: Pair<IntArray, Long>): Problem {
        val numInts = numBinaries + rows.size
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                LongArray(numInts),
                LongArray(numInts) { if (it < numBinaries) 1L else 0L },
                Bits(numInts).also { bits -> for (v in numBinaries until numInts) bits.set(v) },
                Bits(numInts).also { bits -> for (v in numBinaries until numInts) bits.set(v) },
            ),
            factors = rows.mapIndexed { r, (columns, rhs) ->
                Linear(
                    LongArray(columns.size) { 1L } + 2L,
                    columns + (numBinaries + r),
                    LinearOp.EQ,
                    rhs,
                )
            }.toTypedArray<Factor>(),
        )
    }

    @Test
    fun `parities of separate integer equalities combine`() {
        // x0 + x1 is odd and x1 is even, so x0 = 1 and x1 = 0, though neither row alone fixes x0.
        val problem = parityRows(2, intArrayOf(0, 1) to 1L, intArrayOf(1) to 4L)

        val bounds = assertNotNull(XorUnits.deriveXorUnits(problem).bounds)

        assertEquals(listOf(1L to 1L, 0L to 0L), (0..1).map { bounds.lower(it) to bounds.upper(it) })
    }

    @Test
    fun `an odd coefficient on a column wider than 0-1 contributes no parity`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 10)),
            factors = listOf(Linear(longArrayOf(1L, 2L), intArrayOf(0, 1), LinearOp.EQ, 1L)),
        )

        assertTrue(XorUnits.deriveXorUnits(problem).isEmpty)
    }

    @Test
    fun `integer equalities with contradictory parities refute the model`() {
        val problem = parityRows(1, intArrayOf(0) to 1L, intArrayOf(0) to 2L)

        assertTrue(XorUnits.deriveXorUnits(problem).infeasible)
    }

    @Test
    fun `the pass reaches a fixpoint instead of re-adding present units`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true)), targetParity = 0),
                Xor(intArrayOf(Lit.make(1, true)), targetParity = 1),
            ),
        )
        val once = derived(problem)
        assertEquals(
            setOf(Lit.make(0, true), Lit.make(1, true)),
            units(once).toSet(),
            "first run forces both variables",
        )
        assertTrue(Presolve.deriveXorUnits(once.bake()).isEmpty, "re-running adds no duplicate units")
    }

}
