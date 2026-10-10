package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceAffineTest {

    private fun row(vararg terms: Pair<Int, Long>, op: LinearOp, bound: Long) = Linear(
        LongArray(terms.size) { terms[it].second },
        IntArray(terms.size) { terms[it].first },
        op,
        bound,
    )

    /** [n] columns bounded `0..hi`, except those in [open] which are open above. */
    private fun model(n: Int, hi: Long, open: Set<Int>, vararg factors: Factor): Problem = Problem(
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(
            LongArray(n),
            LongArray(n) { hi },
            null,
            if (open.isEmpty()) null else Bits(n).also { b -> open.forEach { b.set(it) } },
        ),
        factors = arrayOf(*factors),
    )

    @Test
    fun `a bounded column is eliminated and rebuilt from its partner`() {
        val problem = model(
            2,
            10L,
            emptySet(),
            row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 0L),
            row(0 to 1L, op = LinearOp.LE, bound = 4L),
        )

        val delta = Presolve.eliminateSourceAffineSingletons(
            problem,
            emptySet(),
            Cancellation.Never,
            AffinePivotOrder.MARKOWITZ,
        )

        assertFalse(delta.isEmpty, "a closed pivot is eliminated")
        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        val ints = longArrayOf(0L, 3L)
        delta.rebuild.rebuildInto(booleanArrayOf(), ints)
        assertEquals(3L, ints[0], "x is rebuilt from y through the defining equality")
        assertTrue(reduced.factors.none { f -> f.intVars.any { it == 0 } }, "the eliminated column is gone")
    }

    @Test
    fun `a column open on a side is never a pivot`() {
        // The same equality, but x is open above. Eliminating it would state no bound row for its terms
        // and leave x unconstrained, so a later bound proof would lose the row it reads.
        val problem = model(
            2,
            10L,
            setOf(0),
            row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 0L),
            row(0 to 1L, op = LinearOp.LE, bound = 4L),
        )

        val delta = Presolve.eliminateSourceAffineSingletons(
            problem,
            emptySet(),
            Cancellation.Never,
            AffinePivotOrder.MARKOWITZ,
        )

        // y is still a lawful pivot, so the pass need not decline outright — what it must not do is take
        // the open column.
        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        assertTrue(reduced.factors.any { f -> f.intVars.any { it == 0 } }, "the open column stays in the model")
    }

    @Test
    fun `a column with declared holes is never a pivot`() {
        // x declares {0, 4}. Folding it out carries only its range onto y, so the rebuild could land x on
        // 1..3. y is held as an objective column, so x is the only pivot the equality offers.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4).excludeValues(longArrayOf(1, 2, 3))!!, IntDomain(0, 10)),
            factors = listOf(
                row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 1L),
                row(0 to 1L, 1 to 1L, op = LinearOp.LE, bound = 9L),
            ),
        )

        val delta = Presolve.eliminateSourceAffineSingletons(
            problem,
            setOf(1),
            Cancellation.Never,
            AffinePivotOrder.MARKOWITZ,
        )

        assertTrue(delta.isEmpty, "a column with holes is never a source pivot")
    }
}
