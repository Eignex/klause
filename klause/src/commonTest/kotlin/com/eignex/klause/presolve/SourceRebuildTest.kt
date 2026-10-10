package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceRebuildTest {

    private fun bools(vararg values: Boolean) = booleanArrayOf(*values)

    private fun pos(v: Int) = Lit.make(v, true)

    private fun neg(v: Int) = Lit.make(v, false)

    /** `x` rebuilt from `(3 - y) / divisor`, rounded toward the side [roundDown] names, with `y = 6`. */
    private fun quotient(divisor: Long, roundDown: Boolean, clamp: Long? = null) = SourceRebuilds(
        listOf(RebuildStep.QuotientValue(0, 3, intArrayOf(1), longArrayOf(-1), divisor, roundDown, clamp)),
    )

    @Test
    fun `a quotient rounded down takes the floor of a negative fraction`() {
        val ints = longArrayOf(0, 6)

        quotient(divisor = 2, roundDown = true).rebuildInto(BooleanArray(0), ints)

        assertEquals(-2L, ints[0], "(3 - 6) / 2 = -1.5, and truncation would give -1")
    }

    @Test
    fun `a quotient rounded up takes the ceiling of a positive fraction`() {
        val ints = longArrayOf(0, 6)

        quotient(divisor = -2, roundDown = false).rebuildInto(BooleanArray(0), ints)

        assertEquals(2L, ints[0], "(3 - 6) / -2 = 1.5")
    }

    @Test
    fun `a quotient is held inside its clamp`() {
        val ints = longArrayOf(0, 6)

        quotient(divisor = 2, roundDown = true, clamp = -5).rebuildInto(BooleanArray(0), ints)

        assertEquals(-5L, ints[0])
    }

    @Test
    fun `a quotient rounds the same way at arbitrary precision`() {
        val ints = arrayOf(BIG_ZERO, bigIntOf(6))

        quotient(divisor = 2, roundDown = true).rebuildInto(BooleanArray(0), ints)

        assertEquals(bigIntOf(-2), ints[0])
    }

    @Test
    fun `a copy from a negative literal takes the opposite value`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = neg(1))))
        val values = bools(true, true)

        rebuild.rebuildInto(values)

        assertFalse(values[0], "a negative source inverts")
    }

    @Test
    fun `an eliminated variable takes the polarity that satisfies an unsatisfied clause`() {
        // `(x0 or x1)` with x1 false leaves the clause to x0, so x0 comes back true.
        val rebuild = SourceRebuilds(
            listOf(RebuildStep.SatisfyClauses(variable = 0, clauses = listOf(intArrayOf(pos(0), pos(1))))),
        )
        val values = bools(false, false)

        rebuild.rebuildInto(values)

        assertTrue(values[0])
    }

    @Test
    fun `a blocked clause is repaired only when it is unsatisfied`() {
        val rebuild = SourceRebuilds(
            listOf(RebuildStep.RepairClause(clause = intArrayOf(pos(0), pos(1)), literal = pos(0))),
        )
        val falsified = bools(false, false)
        val satisfied = bools(false, true)

        rebuild.rebuildInto(falsified)
        rebuild.rebuildInto(satisfied)

        assertTrue(falsified[0], "the blocking literal is forced true")
        assertFalse(satisfied[0], "a satisfied clause leaves every value alone")
    }

    @Test
    fun `composition recovers the later elimination first`() {
        // x0 copies x1, and x1 copies x2. The pass that eliminated x1 ran second, so its column has to be
        // recovered before the step that reads it — otherwise x0 takes a value x1 did not have yet. The
        // list is in the order the passes ran, so this also pins which end of it recovers first.
        val first = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))
        val second = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 1, source = pos(2))))
        val values = bools(false, false, true)

        SourceRebuilds.compose(listOf(first, second)).rebuildInto(values)

        assertTrue(values[1], "the later elimination is recovered first")
        assertTrue(values[0], "so the earlier one reads its recovered value")
    }

    private fun affine(variable: Int, const: Long, vars: IntArray, coeffs: LongArray, divisor: Long = 1) =
        RebuildStep.AffineValue(variable, const, vars, coeffs, divisor)

    @Test
    fun `an eliminated integer column is rebuilt from its partners`() {
        // x0 = 3 + 2*x1, with x1 = 4 -> x0 = 11.
        val rebuild = SourceRebuilds(listOf(affine(0, 3L, intArrayOf(1), longArrayOf(2L))))
        val ints = longArrayOf(0L, 4L)

        rebuild.rebuildInto(booleanArrayOf(), ints)

        assertEquals(11L, ints[0])
    }

    @Test
    fun `a residue pivot divides by its pivot coefficient`() {
        // x0 = (2 + 3*x1) / 5, with x1 = 1 -> 5/5 = 1.
        val rebuild = SourceRebuilds(listOf(affine(0, 2L, intArrayOf(1), longArrayOf(3L), divisor = 5L)))
        val ints = longArrayOf(0L, 1L)

        rebuild.rebuildInto(booleanArrayOf(), ints)

        assertEquals(1L, ints[0])
    }

    @Test
    fun `the wide evaluator carries a value past the Long range`() {
        // 2^70 is the shape an open route answers in and the finite lane cannot hold at all.
        val huge = BIG_ONE shl 70
        val rebuild = SourceRebuilds(listOf(affine(0, 0L, intArrayOf(1), longArrayOf(3L))))
        val ints = arrayOf(BIG_ZERO, huge)

        rebuild.rebuildInto(booleanArrayOf(), ints)

        assertEquals(huge * bigIntOf(3L), ints[0], "the accumulation widens, the coefficient does not")
    }

    @Test
    fun `steps of both kinds recover in one order`() {
        // The reason Boolean and integer steps share a list: an order stated once, not implied between two.
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
}
