package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntegerRowReductionTest {
    @Test
    fun `wide coefficient magnitudes should spend the optional reduction allowance`() {
        val large = bigIntOf(1) shl 4096
        val row = assertNotNull(IntegerConflictRow.source(intArrayOf(0, 1),
            arrayOf(large * bigIntOf(-1), bigIntOf(1)), bigIntOf(-1), useLo = true))
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        val budget = ArithmeticLearningBudget(state, ArithmeticLearningLimits(work = 180))

        val decline = assertFailsWith<ArithmeticLearningDeclined> { row.reduced(state, budget = budget) }

        assertEquals("work", decline.reason)
        assertEquals(listOf(large * bigIntOf(-1), bigIntOf(1)), row.coefficients.toList())
    }

    @Test
    fun `saturation should retain cancellation after overflowing word degree arithmetic`() {
        val inputs = listOf(longArrayOf(2, -2, 1), longArrayOf(1, 1, -1, -1, 2))
        for (coefficients in inputs) {
            val bound = if (coefficients.size == 3) 0L else 1L
            val row = assertNotNull(IntegerConflictRow.source(IntArray(coefficients.size) { it },
                Array(coefficients.size) { bigIntOf(coefficients[it]) }, bigIntOf(bound), useLo = true))
            val state = PropagationState(Problem(0, coefficients.size, Array(coefficients.size) {
                if (it == coefficients.lastIndex) IntDomain(0, 1) else IntDomain(Long.MAX_VALUE, Long.MAX_VALUE)
            }, emptyArray()), Assumptions.None)

            val reduced = assertNotNull(row.reduced(state))

            assertEquals(coefficients.map { bigIntOf(if (it < 0) -1 else 1) }, reduced.coefficients.toList())
            assertEquals(bigIntOf(0), reduced.bound)
        }
    }

    @Test
    fun `saturation should cancel a bound and coefficient beyond signed words`() {
        val large = bigIntOf(1) shl 256
        val row = assertNotNull(IntegerConflictRow.source(intArrayOf(0, 1),
            arrayOf(large, bigIntOf(1)), large, useLo = true))
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)

        val reduced = assertNotNull(row.reduced(state))

        assertEquals(listOf(bigIntOf(1), bigIntOf(1)), reduced.coefficients.toList())
        assertEquals(bigIntOf(1), reduced.bound)
    }

    @Test
    fun `box degree should negate the minimum signed bound exactly`() {
        val row = assertNotNull(IntegerConflictRow.source(intArrayOf(0), arrayOf(bigIntOf(1)),
            bigIntOf(Long.MIN_VALUE), useLo = true))
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 1)),
            emptyArray()), Assumptions.None)

        val reduced = assertNotNull(row.reduced(state))

        assertEquals(bigIntOf(Long.MIN_VALUE), reduced.bound)
        assertEquals(bigIntOf(1), reduced.coefficient(0))
    }

    @Test
    fun `saturation should cap weights at the required integer distance`() {
        val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(-100, -1), intArrayOf(0, 1), LinearOp.LE, -2), useLo = true))
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)

        val reduced = assertNotNull(row.reduced(state))

        assertEquals(bigIntOf(-2), reduced.coefficient(0))
        assertEquals(bigIntOf(-1), reduced.coefficient(1))
        assertEquals(bigIntOf(-2), reduced.bound)
    }

    @Test
    fun `saturation should normalize fresh coefficients without changing its source`() {
        val row = assertNotNull(IntegerConflictRow.source(intArrayOf(0, 1),
            arrayOf(bigIntOf(-100), bigIntOf(-101)), bigIntOf(-2), useLo = true))
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)

        val reduced = assertNotNull(row.reduced(state))

        assertEquals(listOf(bigIntOf(-1), bigIntOf(-1)), reduced.coefficients.toList())
        assertEquals(bigIntOf(-1), reduced.bound)
        assertEquals(listOf(bigIntOf(-100), bigIntOf(-101)), row.coefficients.toList())
        assertEquals(bigIntOf(-2), row.bound)
    }

    @Test
    fun `mixed integer rounding should strengthen a nonnegative cover`() {
        val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(-3, -2), intArrayOf(0, 1), LinearOp.LE, -4), useLo = true))
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 3) }, emptyArray()), Assumptions.None)

        val reduced = assertNotNull(row.reduced(state, bigIntOf(3)))

        assertEquals(bigIntOf(-1), reduced.coefficient(0))
        assertEquals(bigIntOf(-1), reduced.coefficient(1))
        assertEquals(bigIntOf(-2), reduced.bound)
    }

    @Test
    fun `box reductions should preserve feasible assignments across signs and translations`() {
        for (sign in listOf(-1L, 1L)) {
            for (offset in listOf(-3L, 0L, 4L)) {
                val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
                    longArrayOf(3 * sign, -2), intArrayOf(0, 1), LinearOp.LE, offset), useLo = true))
                val state = PropagationState(Problem(0, 2,
                    arrayOf(IntDomain(-2, 3), IntDomain(-1, 4)), emptyArray()), Assumptions.None)
                for (divisor in 1L..5L) {
                    val reduced = assertNotNull(row.reduced(state, bigIntOf(divisor)))
                    for (x in -2L..3L) {
                        for (y in -1L..4L) {
                            if (3 * sign * x - 2 * y > offset) continue
                            val activity = reduced.coefficient(0) * bigIntOf(x) + reduced.coefficient(1) * bigIntOf(y)
                            assertTrue(activity <= reduced.bound,
                                "sign=$sign offset=$offset divisor=$divisor x=$x y=$y")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `branch tightening should not enter a box reduction`() {
        val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(3, -2), intArrayOf(0, 1), LinearOp.LE, 0), useLo = true))
        val state = PropagationState(Problem(0, 2,
            arrayOf(IntDomain(-2, 3), IntDomain(-1, 4)), emptyArray()), Assumptions.None)
        val before = assertNotNull(row.reduced(state, bigIntOf(3)))
        state.undoLogging = true
        assertTrue(state.tightenIntMax(0, 1))
        assertTrue(state.tightenIntMin(1, 2))

        val after = assertNotNull(row.reduced(state, bigIntOf(3)))

        assertTrue(before.subsumes(after))
        assertTrue(after.subsumes(before))
    }
}
