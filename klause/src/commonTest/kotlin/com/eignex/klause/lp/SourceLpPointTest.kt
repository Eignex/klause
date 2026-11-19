package com.eignex.klause.lp

import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceLpPointTest {
    @Test
    fun `strict residual chains should complete beyond tableau cell admission`() {
        val columns = 1024
        val chain = 64
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
        ) + List(chain - 1) {
            ExactRationalInequality(intArrayOf(it, it + 1), listOf(BigFraction.ONE, BigFraction.MINUS_ONE),
                BigFraction.ZERO, strict = true)
        } + exactColumnUpper(chain - 1, BigFraction.ONE)

        val point = assertNotNull(sourceLpPoint(rows, columns, SourceLpBudget(), ExecutionControl.Never))

        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `unsafe sparse dimensions should decline before allocating column metadata`() {
        val budget = SourceLpBudget()

        val point = sourceLpPoint(emptyList(), Int.MAX_VALUE, budget, ExecutionControl.Never)

        assertNull(point)
        assertTrue(budget.measuredPreparationWork > 0L)
        assertEquals(0L, budget.measuredFloatWork)
    }

    @Test
    fun `fractional strict sides should yield an exact interior point`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ofLong(2)), BigFraction.ONE, strict = true),
        )

        val point = assertNotNull(sourceLpPoint(rows, 1, SourceLpBudget(), ExecutionControl.Never))

        assertEquals(1, point.size)
        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `unrelated rational denominators should retain exact coordinates`() {
        val values = listOf(BigFraction.ofLong(3037000499L).reciprocal(),
            BigFraction.ofLong(3037000501L).reciprocal())
        val rows = values.flatMapIndexed { column, value ->
            listOf(exactColumnLower(column, value), exactColumnUpper(column, value))
        }

        val point = sourceLpPoint(rows, 2, SourceLpBudget(), ExecutionControl.Never)

        assertEquals(values, point)
    }

    @Test
    fun `strict zero sum cycles should withhold a point`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.MINUS_ONE),
                BigFraction.ZERO, strict = true),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.MINUS_ONE, BigFraction.ONE),
                BigFraction.ZERO),
        )

        val point = sourceLpPoint(rows, 2, SourceLpBudget(), ExecutionControl.Never)

        assertNull(point)
    }

    @Test
    fun `sparse residuals should complete beyond dense dimension admission`() {
        val rows = List(128) {
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true)
        } + exactColumnUpper(0, BigFraction.ONE)
        val budget = SourceLpBudget()

        val point = assertNotNull(sourceLpPoint(rows, 1, budget, ExecutionControl.Never))

        assertTrue(point.satisfiesSourceRows(rows))
        assertTrue(budget.measuredPreparationWork > 0L)
    }

    @Test
    fun `exhausted recovery should retain charged work and withhold its point`() {
        var spent = 0L
        val stop = object : ExecutionControl() {
            override fun isCancelled(): Boolean = spent > 0L
            override val accounting: WorkMeter get() = WorkMeter { spent += it }
        }
        val rows = listOf(exactColumnLower(0, BigFraction.ONE))
        val budget = SourceLpBudget()

        val point = sourceLpPoint(rows, 1, budget, stop)

        assertNull(point)
        assertTrue(spent > 0L)
        assertEquals(spent, budget.measuredPreparationWork)
        assertEquals(0L, budget.measuredFloatWork)
    }
}
