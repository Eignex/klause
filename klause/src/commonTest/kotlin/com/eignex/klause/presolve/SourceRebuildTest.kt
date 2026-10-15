package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceRebuildTest {
    private fun bools(vararg values: Boolean) = booleanArrayOf(*values)

    private fun pos(v: Int) = Lit.make(v, true)

    private fun neg(v: Int) = Lit.make(v, false)

    private fun quotient(divisor: Long, roundDown: Boolean, clamp: Long? = null) = SourceRebuilds(
        listOf(RebuildStep.QuotientValue(0, 3, intArrayOf(1), longArrayOf(-1), divisor, roundDown, clamp)),
    )

    @Test
    fun `a quotient rounded down should take the floor of a negative fraction`() {
        val ints = longArrayOf(0, 6)

        quotient(divisor = 2, roundDown = true).rebuildInto(BooleanArray(0), ints)

        assertEquals(-2L, ints[0], "(3 - 6) / 2 = -1.5, and truncation would give -1")
    }

    @Test
    fun `a quotient rounded up should take the ceiling of a positive fraction`() {
        val ints = longArrayOf(0, 6)

        quotient(divisor = -2, roundDown = false).rebuildInto(BooleanArray(0), ints)

        assertEquals(2L, ints[0], "(3 - 6) / -2 = 1.5")
    }

    @Test
    fun `a quotient should be held inside its clamp`() {
        val ints = longArrayOf(0, 6)

        quotient(divisor = 2, roundDown = true, clamp = -5).rebuildInto(BooleanArray(0), ints)

        assertEquals(-5L, ints[0])
    }

    @Test
    fun `a quotient should round the same way at arbitrary precision`() {
        val ints = arrayOf(BIG_ZERO, bigIntOf(6))

        quotient(divisor = 2, roundDown = true).rebuildInto(BooleanArray(0), ints)

        assertEquals(bigIntOf(-2), ints[0])
    }

    @Test
    fun `a copy from a negative literal should take the opposite value`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = neg(1))))
        val values = bools(true, true)

        rebuild.rebuildInto(values)

        assertFalse(values[0])
    }

    @Test
    fun `an eliminated variable should take the polarity that satisfies an unsatisfied clause`() {
        val rebuild = SourceRebuilds(
            listOf(RebuildStep.SatisfyClauses(variable = 0, clauses = listOf(intArrayOf(pos(0), pos(1))))),
        )
        val values = bools(false, false)

        rebuild.rebuildInto(values)

        assertTrue(values[0])
    }

    @Test
    fun `a blocked clause should be repaired only when it is unsatisfied`() {
        val rebuild = SourceRebuilds(
            listOf(RebuildStep.RepairClause(clause = intArrayOf(pos(0), pos(1)), literal = pos(0))),
        )
        val falsified = bools(false, false)
        val satisfied = bools(false, true)

        rebuild.rebuildInto(falsified)
        rebuild.rebuildInto(satisfied)

        assertTrue(falsified[0])
        assertFalse(satisfied[0])
    }

    @Test
    fun `composition should recover the later elimination first`() {
        // x1 was eliminated by the later pass, so it must be recovered before x0 reads it.
        val first = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))
        val second = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 1, source = pos(2))))
        val values = bools(false, false, true)

        SourceRebuilds.compose(listOf(first, second)).rebuildInto(values)

        assertTrue(values[1])
        assertTrue(values[0])
    }

    private fun affine(variable: Int, const: Long, vars: IntArray, coeffs: LongArray, divisor: Long = 1) =
        RebuildStep.AffineValue(variable, const, vars, coeffs, divisor)

    @Test
    fun `an eliminated integer column should be rebuilt from its partners`() {
        val rebuild = SourceRebuilds(listOf(affine(0, 3L, intArrayOf(1), longArrayOf(2L))))
        val ints = longArrayOf(0L, 4L)

        rebuild.rebuildInto(booleanArrayOf(), ints)

        assertEquals(11L, ints[0])
    }

    @Test
    fun `a residue pivot should divide by its pivot coefficient`() {
        val rebuild = SourceRebuilds(listOf(affine(0, 2L, intArrayOf(1), longArrayOf(3L), divisor = 5L)))
        val ints = longArrayOf(0L, 1L)

        rebuild.rebuildInto(booleanArrayOf(), ints)

        assertEquals(1L, ints[0])
    }

    @Test
    fun `the wide evaluator should carry a value past the Long range`() {
        val huge = BIG_ONE shl 70
        val rebuild = SourceRebuilds(listOf(affine(0, 0L, intArrayOf(1), longArrayOf(3L))))
        val ints = arrayOf(BIG_ZERO, huge)

        rebuild.rebuildInto(booleanArrayOf(), ints)

        assertEquals(huge * bigIntOf(3L), ints[0])
    }

    @Test
    fun `boolean and integer steps should recover in one order`() {
        val rebuild = SourceRebuilds(
            listOf(
                affine(0, 1L, intArrayOf(1), longArrayOf(1L)),
                RebuildStep.CopyLiteral(variable = 0, source = pos(1)),
            ),
        )
        val bools = bools(false, true)
        val ints = longArrayOf(0L, 9L)

        rebuild.rebuildInto(bools, ints)

        assertEquals(10L, ints[0])
        assertTrue(bools[0])
        assertTrue(rebuild.touchesInts)
    }

    @Test
    fun `affine reconstruction should retain exact cancellation beyond Long intermediates`() {
        val rebuild = SourceRebuilds(
            listOf(
                RebuildStep.AffineValue(0, -Long.MAX_VALUE, intArrayOf(1), longArrayOf(2), 1),
            ),
        )
        val ints = longArrayOf(0, Long.MAX_VALUE)

        rebuild.rebuildInto(BooleanArray(0), ints)

        assertEquals(Long.MAX_VALUE, ints[0])
    }

    @Test
    fun `affine reconstruction should reject nonintegral reduced values`() {
        val rebuild = SourceRebuilds(
            listOf(
                RebuildStep.AffineValue(0, 0, intArrayOf(1), longArrayOf(1), 2),
            ),
        )

        assertFailsWith<IllegalArgumentException> { rebuild.rebuildInto(BooleanArray(0), longArrayOf(0, 3)) }
    }

    @Test
    fun `reconstruction records should retain their own coefficient arrays`() {
        val coefficients = longArrayOf(2)
        val rebuild = SourceRebuilds(listOf(RebuildStep.AffineValue(0, 1, intArrayOf(1), coefficients, 1)))
        coefficients[0] = 9
        val ints = longArrayOf(0, 3)

        rebuild.rebuildInto(BooleanArray(0), ints)

        assertEquals(7L, ints[0])
    }
}
