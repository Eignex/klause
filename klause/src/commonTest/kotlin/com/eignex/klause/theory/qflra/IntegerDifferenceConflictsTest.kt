package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.constsOf
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.exactForm
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IntegerDifferenceConflictsTest {
    @Test
    fun `opposite coefficients should normalize exactly into guarded negative cycles`() {
        for (sign in listOf(1, -1)) {
            val open = Bits(2).also { bits -> repeat(2, bits::set) }
            val model = Problem(2,
                intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(2 * sign, -2 * sign), intArrayOf(0, 1), LinearOp.LE, 0),
                    ReifiedLinear(1, intArrayOf(-2 * sign, 2 * sign), intArrayOf(0, 1), LinearOp.LE, -2),
                ))
            val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
            val system = assertNotNull(IntegerDifferenceConflicts.prepare(model,
                model.factors.asSequence().flatMap { it.linearRows.asSequence() }.map {
                    IntegerDifferenceConflicts.Row(it, it.exactForm(0), true)
                }, stop))

            val conflict = assertIs<IntegerDifferenceConflicts.Result.Conflict>(system.check(intArrayOf(1, 1), stop))

            assertContentEquals(intArrayOf(Lit.make(0, false), Lit.make(1, false)), conflict.clause)
            assertIs<IntegerDifferenceConflicts.Result.NoConflict>(system.check(intArrayOf(1, -1), stop))
        }
    }

    @Test
    fun `a supported subset should explain a conflict while leaving unsupported rows optional`() {
        val model = Problem(1,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3),
                Bits(3).also { bits -> for (column in 0..2) bits.set(column) },
                Bits(3).also { bits -> for (column in 0..2) bits.set(column) }),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 5),
            ))
        val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
        val system = assertNotNull(IntegerDifferenceConflicts.prepare(model,
            model.factors.asSequence().flatMap { it.linearRows.asSequence() }.map {
                IntegerDifferenceConflicts.Row(it, it.exactForm(0), true)
            }, stop))

        val conflict = assertIs<IntegerDifferenceConflicts.Result.Conflict>(system.check(intArrayOf(1), stop))

        assertContentEquals(intArrayOf(Lit.make(0, false)), conflict.clause)
        assertIs<IntegerDifferenceConflicts.Result.NoConflict>(system.check(intArrayOf(-1), stop))
        assertIs<IntegerDifferenceConflicts.Result.NoConflict>(system.check(intArrayOf(0), stop))
        assertIs<IntegerDifferenceConflicts.Result.Conflict>(system.check(intArrayOf(1), stop))
    }

    @Test
    fun `equality and disequality should contribute only their equality alternatives`() {
        for ((op, truth) in listOf(LinearOp.EQ to 1, LinearOp.NE to 0)) {
            val model = Problem(1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(1), longArrayOf(2), null, null),
                factors = arrayOf(ReifiedLinear(0, intArrayOf(-1), intArrayOf(0), op, 0)))
            val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
            val system = assertNotNull(IntegerDifferenceConflicts.prepare(model,
                model.factors.asSequence().flatMap { it.linearRows.asSequence() }.map {
                    IntegerDifferenceConflicts.Row(it, it.exactForm(0), true)
                }, stop))

            val conflict = assertIs<IntegerDifferenceConflicts.Result.Conflict>(system.check(intArrayOf(truth), stop))

            assertContentEquals(intArrayOf(Lit.make(0, truth == 0)), conflict.clause)
            assertIs<IntegerDifferenceConflicts.Result.NoConflict>(system.check(intArrayOf(1 - truth), stop))
        }
    }

    @Test
    fun `strict complemented rows should retain both guard polarities`() {
        val model = Problem(2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            ))
        val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
        val system = assertNotNull(IntegerDifferenceConflicts.prepare(model,
            model.factors.asSequence().flatMap { it.linearRows.asSequence() }.map {
                IntegerDifferenceConflicts.Row(it, it.exactForm(0), true)
            }, stop))

        val conflict = assertIs<IntegerDifferenceConflicts.Result.Conflict>(system.check(intArrayOf(1, 0), stop))

        assertContentEquals(intArrayOf(Lit.make(0, false), Lit.make(1, true)), conflict.clause)
        assertIs<IntegerDifferenceConflicts.Result.NoConflict>(system.check(intArrayOf(1, 1), stop))
        assertIs<IntegerDifferenceConflicts.Result.NoConflict>(system.check(intArrayOf(0, 0), stop))
    }

    @Test
    fun `unconditional contradictory bounds should need no guard`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(1), longArrayOf(0), null, null),
            factors = emptyArray())
        val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
        val system = assertNotNull(IntegerDifferenceConflicts.prepare(model, emptySequence(), stop))

        val conflict = assertIs<IntegerDifferenceConflicts.Result.Conflict>(system.check(intArrayOf(), stop))

        assertContentEquals(intArrayOf(), conflict.clause)
    }

    @Test
    fun `explicit extreme bounds should be skipped safely without inventing a conflict`() {
        for ((lower, upper) in listOf(Long.MIN_VALUE to 0L, 0L to Long.MAX_VALUE)) {
            val model = Problem(0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(lower), longArrayOf(upper), null, null),
                factors = emptyArray())
            val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
            val system = assertNotNull(IntegerDifferenceConflicts.prepare(model, emptySequence(), stop))

            val result = system.check(intArrayOf(), stop)

            assertIs<IntegerDifferenceConflicts.Result.NoConflict>(result)
        }
    }

    @Test
    fun `unsupported normalized or unsafe weights should supply no deduction`() {
        for ((coefficient, bound) in listOf(2 to 1L, 1 to Long.MIN_VALUE, -1 to Long.MAX_VALUE)) {
            val model = Problem(0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(1), longArrayOf(2), null, null),
                factors = arrayOf(Linear(longArrayOf(coefficient.toLong()), intArrayOf(0), LinearOp.LE, bound)))
            val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
            val system = assertNotNull(IntegerDifferenceConflicts.prepare(model,
                model.factors.asSequence().flatMap { it.linearRows.asSequence() }.map {
                    IntegerDifferenceConflicts.Row(it, it.exactForm(0), true)
                }, stop))

            val result = system.check(intArrayOf(), stop)

            assertIs<IntegerDifferenceConflicts.Result.NoConflict>(result)
        }
    }

    @Test
    fun `an alternative row should never become an unconditional edge`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, -1)))
        val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
        val system = assertNotNull(IntegerDifferenceConflicts.prepare(model,
            model.factors.asSequence().flatMap { it.linearRows.asSequence() }.map {
                IntegerDifferenceConflicts.Row(it, it.exactForm(0), false)
            }, stop))

        val result = system.check(intArrayOf(), stop)

        assertIs<IntegerDifferenceConflicts.Result.NoConflict>(result)
    }

    @Test
    fun `Boolean coefficient terms should retain the LP obligation`() {
        val model = Problem(1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray())
        val row = TaggedLinearRow(intArrayOf(Term.ofIntVar(0), Term.ofLit(Lit.make(0, true))),
            IntegerConstants(constsOf(longArrayOf(1, 1)), -1L), LinearOp.LE)
        val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
        val system = assertNotNull(IntegerDifferenceConflicts.prepare(model,
            sequenceOf(IntegerDifferenceConflicts.Row(row, row.exactForm(0), true)), stop))

        val result = system.check(intArrayOf(1), stop)

        assertIs<IntegerDifferenceConflicts.Result.NoConflict>(result)
    }

    @Test
    fun `cancellation should withhold preparation and graph conflicts without refunding work`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(1), longArrayOf(0), null, null),
            factors = emptyArray())
        var spent = 0L
        val token = object : Cancellation {
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            override fun isCancelled(): Boolean = spent > 0L
        }
        val cancelled = IntegerDifferenceAccounting(token) { _, _ -> }

        val prepared = IntegerDifferenceConflicts.prepare(model, emptySequence(), cancelled)

        assertNull(prepared)
        assertEquals(1L, spent)
        val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
        val system = assertNotNull(IntegerDifferenceConflicts.prepare(model, emptySequence(), stop))
        assertIs<IntegerDifferenceConflicts.Result.Interrupted>(system.check(intArrayOf(), cancelled))
        spent = 0L
        val graphStop = object : Cancellation {
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            override fun isCancelled(): Boolean = spent > 6L
        }
        assertIs<IntegerDifferenceConflicts.Result.Interrupted>(
            system.check(intArrayOf(), IntegerDifferenceAccounting(graphStop) { _, _ -> }),
        )
        assertEquals(8L, spent)
        assertIs<IntegerDifferenceConflicts.Result.Conflict>(system.check(intArrayOf(), stop))
    }

    @Test
    fun `an observed stop should prevent the next equality edge charge`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)))
        var spent = 0L
        val token = object : Cancellation {
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            override fun isCancelled(): Boolean = spent >= 10L
        }
        val stop = IntegerDifferenceAccounting(token) { _, _ -> }

        val system = IntegerDifferenceConflicts.prepare(model,
            model.factors.asSequence().flatMap { it.linearRows.asSequence() }.map {
                IntegerDifferenceConflicts.Row(it, it.exactForm(0), true)
            }, stop)

        assertNull(system)
        assertEquals(10L, spent)
    }
}
