package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WholeRealPointTest {
    @Test
    fun `primitive truths should retain strict equality and disequality semantics`() {
        for (relation in LinearOp.entries) {
            for (strict in listOf(false, true).filter { !it || relation == LinearOp.LE || relation == LinearOp.GE }) {
                val source = Problem(1, 0, emptyArray(), arrayOf(
                    ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), relation,
                        BigFraction.ZERO, strict = strict, activator = 0),
                ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                    realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
                for (value in -1L..1L) {
                    val expected = when (relation) {
                        LinearOp.LE -> if (strict) value < 0L else value <= 0L
                        LinearOp.GE -> if (strict) value > 0L else value >= 0L
                        LinearOp.EQ -> value == 0L
                        LinearOp.NE -> value != 0L
                    }

                    val result = wholeRealPointTruths(source, listOf(BigFraction.ofLong(value)), Cancellation.Never)

                    assertContentEquals(intArrayOf(if (expected) 1 else 0), result)
                }
            }
        }
    }

    @Test
    fun `whole point completion should preserve every current Boolean pin`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.EQ,
                BigFraction.ZERO, activator = 0),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        var calls = 0

        val conflicting = completeWholeRealPoint(source, listOf(BigFraction.ZERO), intArrayOf(0, -1),
            Cancellation.Never) { _, _ -> calls++; booleanArrayOf(true, false) }
        val compatible = completeWholeRealPoint(source, listOf(BigFraction.ZERO), intArrayOf(1, 1),
            Cancellation.Never) { _, _ -> calls++; booleanArrayOf(true, true) }
        val changed = completeWholeRealPoint(source, listOf(BigFraction.ZERO), intArrayOf(1, 1),
            Cancellation.Never) { guesses, _ -> guesses.fill(-1); booleanArrayOf(true, false) }

        assertNull(conflicting)
        assertContentEquals(booleanArrayOf(true, true), assertNotNull(compatible).bools)
        assertNull(changed)
        assertEquals(2, calls)
    }

    @Test
    fun `rows sharing an activator should agree at the proposed point`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.EQ,
                BigFraction.ZERO, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.NE,
                BigFraction.ZERO, activator = 0),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))

        val result = wholeRealPointTruths(source, listOf(BigFraction.ZERO), Cancellation.Never)

        assertNull(result)
    }

    @Test
    fun `a necessary point should satisfy unconditional source rows and declared bounds`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.GE, BigFraction.ONE),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))

        for (value in listOf(-1L, 0L, 2L)) {
            assertNull(wholeRealPointTruths(source, listOf(BigFraction.ofLong(value)), Cancellation.Never))
        }
        assertContentEquals(intArrayOf(-1), wholeRealPointTruths(source, listOf(BigFraction.ONE), Cancellation.Never))
    }

    @Test
    fun `cancelled whole point arithmetic should withhold its guesses and retain work`() {
        val source = Problem(1, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        var spent = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = spent > 0L
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = wholeRealPointTruths(source, listOf(BigFraction.ZERO), stop)

        assertNull(result)
        assertTrue(spent > 0L)
        assertNotNull(wholeRealPointTruths(source, listOf(BigFraction.ZERO), Cancellation.Never))
    }
}
