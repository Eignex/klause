package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MakespanLinksTest {

    private fun offsets(factors: Array<Factor>, vars: Int, target: Int, of: Int): Long {
        val p = Problem(0, vars, Array(vars) { IntDomain(0L, 100L) }, factors)
        var offset = Long.MIN_VALUE
        sequence<Unit> {
            val links = prepareMakespanLinks(p)
            offset = with(links) { offsetsTo(target) }.getOrDefault(of, Long.MIN_VALUE)
        }.forEach { }
        return offset
    }

    @Test
    fun `offsets should add precedence constants along a chain into a maximum`() {
        val factors = arrayOf<Factor>(
            ArrayMinMax(result = 0, xs = intArrayOf(1, 4), max = true),
            Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.GE, 3L),
            Linear(longArrayOf(-1, 1), intArrayOf(2, 3), LinearOp.LE, -2L),
        )
        assertEquals(5L, offsets(factors, vars = 5, target = 0, of = 3))
    }

    @Test
    fun `offsets should keep the longest of two chains`() {
        val factors = arrayOf<Factor>(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 1L),
            Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.GE, 1L),
            Linear(longArrayOf(1, -1), intArrayOf(0, 2), LinearOp.GE, 1L),
        )
        assertEquals(2L, offsets(factors, vars = 3, target = 0, of = 2))
    }

    @Test
    fun `offsets should ignore rows without a unit coefficient pair`() {
        val factors = arrayOf<Factor>(Linear(longArrayOf(2, -1), intArrayOf(0, 1), LinearOp.GE, 1L))
        assertEquals(Long.MIN_VALUE, offsets(factors, vars = 2, target = 0, of = 1))
    }

    @Test
    fun `offsets should stop on a positive cycle`() {
        val factors = arrayOf<Factor>(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 1L),
            Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.GE, 1L),
            Linear(longArrayOf(1, -1), intArrayOf(2, 1), LinearOp.GE, 1L),
        )
        assertTrue(offsets(factors, vars = 3, target = 0, of = 2) >= 2L)
    }
}
