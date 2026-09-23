package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The declarative Boolean reconstruction the source lane carries. Each step is checked by what it
 * recovers, and the composition by the order it recovers in — an elimination reads the columns of the
 * model it produced, so a later pass's columns come back first.
 */
class BoolRebuildTest {

    private fun bools(vararg values: Boolean) = booleanArrayOf(*values)

    private fun pos(v: Int) = Lit.make(v, true)

    private fun neg(v: Int) = Lit.make(v, false)

    @Test
    fun `a copy takes the value of its source literal`() {
        val rebuild = BoolRebuilds(listOf(BoolRebuild.CopyLiteral(variable = 0, source = pos(1))))
        val values = bools(false, true)

        rebuild.rebuildInto(values)

        assertTrue(values[0], "the merged column takes its representative's value")
    }

    @Test
    fun `a copy from a negative literal takes the opposite value`() {
        val rebuild = BoolRebuilds(listOf(BoolRebuild.CopyLiteral(variable = 0, source = neg(1))))
        val values = bools(true, true)

        rebuild.rebuildInto(values)

        assertFalse(values[0], "a negative source inverts")
    }

    @Test
    fun `an eliminated variable takes the polarity that satisfies an unsatisfied clause`() {
        // `(x0 or x1)` with x1 false leaves the clause to x0, so x0 comes back true.
        val rebuild = BoolRebuilds(
            listOf(BoolRebuild.SatisfyClauses(variable = 0, clauses = listOf(intArrayOf(pos(0), pos(1))))),
        )
        val values = bools(false, false)

        rebuild.rebuildInto(values)

        assertTrue(values[0])
    }

    @Test
    fun `an eliminated variable whose clauses already hold comes back false`() {
        // x1 satisfies the clause on its own, so nothing is forced and the default stands.
        val rebuild = BoolRebuilds(
            listOf(BoolRebuild.SatisfyClauses(variable = 0, clauses = listOf(intArrayOf(pos(0), pos(1))))),
        )
        val values = bools(true, true)

        rebuild.rebuildInto(values)

        assertFalse(values[0])
    }

    @Test
    fun `a blocked clause is repaired only when it is unsatisfied`() {
        val rebuild = BoolRebuilds(
            listOf(BoolRebuild.RepairClause(clause = intArrayOf(pos(0), pos(1)), literal = pos(0))),
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
        val first = BoolRebuilds(listOf(BoolRebuild.CopyLiteral(variable = 0, source = pos(1))))
        val second = BoolRebuilds(listOf(BoolRebuild.CopyLiteral(variable = 1, source = pos(2))))
        val values = bools(false, false, true)

        BoolRebuilds.compose(listOf(first, second)).rebuildInto(values)

        assertTrue(values[1], "the later elimination is recovered first")
        assertTrue(values[0], "so the earlier one reads its recovered value")
    }

    @Test
    fun `composing rebuilds that recover nothing recovers nothing`() {
        assertTrue(BoolRebuilds.compose(emptyList()).isEmpty)
        assertTrue(BoolRebuilds.compose(listOf(BoolRebuilds.NONE, BoolRebuilds.NONE)).isEmpty)
        assertTrue(BoolRebuilds.NONE.isEmpty)
    }

    @Test
    fun `composing leaves a lone rebuild's steps in place`() {
        val rebuild = BoolRebuilds(listOf(BoolRebuild.CopyLiteral(variable = 0, source = pos(1))))
        val values = bools(false, true)

        BoolRebuilds.compose(listOf(BoolRebuilds.NONE, rebuild, BoolRebuilds.NONE)).rebuildInto(values)

        assertTrue(values[0])
    }

    @Test
    fun `a rebuild that recovers nothing yields no sample lift`() {
        // The finite lane reads the absence to mean the identity, so an empty rebuild must not hand it a
        // lambda that copies every solution's arrays for nothing.
        assertEquals(null, BoolRebuilds.NONE.asSampleLift())
    }

    @Test
    fun `the sample lift leaves the solved sample alone`() {
        val rebuild = BoolRebuilds(listOf(BoolRebuild.CopyLiteral(variable = 0, source = pos(1))))
        val solved = Sample(bools(false, true), longArrayOf(7))

        val lifted = checkNotNull(rebuild.asSampleLift())(solved)

        assertTrue(lifted.bools[0], "the eliminated column is recovered")
        assertFalse(solved.bools[0], "the sample the search produced is not written through")
        assertEquals(7L, lifted.ints[0], "columns the rebuild does not name are carried")
    }
}
