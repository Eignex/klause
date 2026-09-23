package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Sample
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The declarative Boolean reconstruction the source lane carries. Each step is checked by what it
 * recovers, and the composition by the order it recovers in — an elimination reads the columns of the
 * model it produced, so a later pass's columns come back first.
 */
class SourceRebuildTest {

    private fun bools(vararg values: Boolean) = booleanArrayOf(*values)

    private fun pos(v: Int) = Lit.make(v, true)

    private fun neg(v: Int) = Lit.make(v, false)

    @Test
    fun `a copy takes the value of its source literal`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))
        val values = bools(false, true)

        rebuild.rebuildInto(values)

        assertTrue(values[0], "the merged column takes its representative's value")
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
    fun `an eliminated variable whose clauses already hold comes back false`() {
        // x1 satisfies the clause on its own, so nothing is forced and the default stands.
        val rebuild = SourceRebuilds(
            listOf(RebuildStep.SatisfyClauses(variable = 0, clauses = listOf(intArrayOf(pos(0), pos(1))))),
        )
        val values = bools(true, true)

        rebuild.rebuildInto(values)

        assertFalse(values[0])
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

    @Test
    fun `composing rebuilds that recover nothing recovers nothing`() {
        assertTrue(SourceRebuilds.compose(emptyList()).isEmpty)
        assertTrue(SourceRebuilds.compose(listOf(SourceRebuilds.NONE, SourceRebuilds.NONE)).isEmpty)
    }

    @Test
    fun `composing skips the rebuilds that recover nothing`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))
        val values = bools(false, true)

        SourceRebuilds.compose(listOf(SourceRebuilds.NONE, rebuild, SourceRebuilds.NONE)).rebuildInto(values)

        assertTrue(values[0])
    }

    @Test
    fun `a rebuild that recovers nothing yields no sample lift`() {
        // The finite lane reads the absence to mean the identity, so an empty rebuild must not hand it a
        // lambda that copies every solution's arrays for nothing.
        assertEquals(null, SourceRebuilds.NONE.asSampleLift())
    }

    @Test
    fun `the sample lift leaves the solved sample alone`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))
        val solved = Sample(bools(false, true), longArrayOf(7))

        val lifted = checkNotNull(rebuild.asSampleLift())(solved)

        assertTrue(lifted.bools[0], "the eliminated column is recovered")
        assertFalse(solved.bools[0], "the sample the search produced is not written through")
        assertEquals(7L, lifted.ints[0], "columns the rebuild does not name are carried")
    }

    // ---- integer columns ----

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
    fun `the two evaluators agree on values a Long holds`() {
        // The whole point of stating the step as data: one record, two widths, one answer.
        val rebuild = SourceRebuilds(
            listOf(
                affine(0, -7L, intArrayOf(1, 2), longArrayOf(3L, -2L)),
                affine(3, 1L, intArrayOf(0), longArrayOf(4L)),
            ),
        )
        val long = longArrayOf(0L, 5L, 6L, 0L)
        val big = Array(4) { BigInteger.fromLong(long[it]) }

        rebuild.rebuildInto(booleanArrayOf(), long)
        rebuild.rebuildInto(booleanArrayOf(), big)

        for (v in long.indices) assertEquals(long[v].toString(), big[v].toString(), "lanes disagree on column $v")
    }

    @Test
    fun `the wide evaluator carries a value past the Long range`() {
        // 2^70 is the shape an open route answers in and the finite lane cannot hold at all.
        val huge = BigInteger.fromLong(2L).pow(70)
        val rebuild = SourceRebuilds(listOf(affine(0, 0L, intArrayOf(1), longArrayOf(3L))))
        val ints = arrayOf(BigInteger.ZERO, huge)

        rebuild.rebuildInto(booleanArrayOf(), ints)

        assertEquals(huge * BigInteger.fromLong(3L), ints[0], "the accumulation widens, the coefficient does not")
    }

    @Test
    fun `a boolean-only rebuild touches no integer column`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))

        assertFalse(rebuild.touchesInts, "a lane with no integer step materializes no values")
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
