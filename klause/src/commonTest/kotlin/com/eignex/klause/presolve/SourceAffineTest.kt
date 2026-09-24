package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
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

/**
 * Affine elimination over a source model. What separates it from the finite form is what a declaration
 * states: a column open on either side is no pivot, and the residue-class doubletons are left alone.
 */
class SourceAffineTest {

    private fun row(vararg terms: Pair<Int, Long>, op: LinearOp, bound: Long) = Linear(
        LongArray(terms.size) { terms[it].second },
        IntArray(terms.size) { terms[it].first },
        op,
        bound,
    )

    /** Whether every linear row of [problem] holds at [ints]. */
    private fun satisfies(problem: Problem, ints: LongArray): Boolean = problem.factors
        .filterIsInstance<Linear>()
        .all { f ->
            val row = f.integerConstants ?: return@all true
            var lhs = 0L
            for (i in f.vars.indices) lhs += row.coeff(i) * ints[f.vars[i]]
            when (f.op) {
                LinearOp.LE -> lhs <= row.bound
                LinearOp.GE -> lhs >= row.bound
                LinearOp.EQ -> lhs == row.bound
                LinearOp.NE -> lhs != row.bound
            }
        }

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
    fun `an open partner does not stop a bounded pivot`() {
        // y is open, x is not. Folding x out states both of its bound rows over y, which is exactly the
        // information the equality carried — so the fold costs a later bound proof nothing.
        val problem = model(
            2,
            10L,
            setOf(1),
            row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 0L),
            row(0 to 1L, op = LinearOp.LE, bound = 4L),
        )

        val delta = Presolve.eliminateSourceAffineSingletons(
            problem,
            emptySet(),
            Cancellation.Never,
            AffinePivotOrder.MARKOWITZ,
        )

        assertFalse(delta.isEmpty, "a bounded pivot folds even beside an open partner")
        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        // The fold keeps what the equality said: every value of the surviving column that the reduced
        // model admits lifts to a pair the input admits.
        for (y in 0L..10L) {
            val ints = longArrayOf(0L, y)
            if (!satisfies(reduced, ints.copyOf().also { delta.rebuild.rebuildInto(booleanArrayOf(), it) })) continue
            val lifted = longArrayOf(0L, y).also { delta.rebuild.rebuildInto(booleanArrayOf(), it) }
            assertTrue(satisfies(problem, lifted), "rebuild produced ${lifted.toList()}, not a solution")
        }
    }

    @Test
    fun `a binary column is left to the substitution that turns it into a literal`() {
        // Both columns declare {0, 1}. Eliminating one takes it out of SUBSTITUTE_BINARY_COLUMNS' reach
        // and leaves a model that was going to be pure pseudo-Boolean as a hybrid, which costs it the
        // lane rather than a reduction.
        val problem = model(
            2,
            1L,
            emptySet(),
            row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 0L),
            row(0 to 1L, 1 to 1L, op = LinearOp.LE, bound = 1L),
        )

        val delta = Presolve.eliminateSourceAffineSingletons(
            problem,
            emptySet(),
            Cancellation.Never,
            AffinePivotOrder.MARKOWITZ,
        )

        assertTrue(delta.isEmpty, "a {0, 1} column is never a source pivot")
    }
}
