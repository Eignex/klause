package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntegerRowReductionTest {
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
                            assertTrue(activity <= reduced.bound, "sign=$sign offset=$offset divisor=$divisor x=$x y=$y")
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
