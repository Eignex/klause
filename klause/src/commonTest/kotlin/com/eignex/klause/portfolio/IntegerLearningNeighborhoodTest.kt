package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegerLearningNeighborhoodTest {
    @Test
    fun `signed and scaled compact neighbors should offer a nonempty resolution`() {
        val sources = listOf(
            Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 1) to
                Linear(longArrayOf(-1, 1), intArrayOf(2, 3), LinearOp.LE, 0),
            Linear(longArrayOf(-2, -2, -2), intArrayOf(0, 1, 2), LinearOp.EQ, -2) to
                Linear(longArrayOf(3, -3), intArrayOf(2, 3), LinearOp.GE, 0),
        )
        for ((equality, neighbor) in sources) {
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(equality, neighbor))

            val neighborhood = IntegerLearningNeighborhood.inspect(problem)

            assertTrue(neighborhood.compatible)
        }
    }

    @Test
    fun `disjoint empty or oversized combinations should retain clause only eligibility`() {
        val equality = Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 1)
        val sources = listOf(
            equality to Linear(longArrayOf(-1, 1), intArrayOf(3, 4), LinearOp.LE, 0),
            equality to equality,
            equality to Linear(LongArray(17) { 1L }, IntArray(17) { it + 2 }, LinearOp.LE, 8),
            Linear(LongArray(16) { 1L }, IntArray(16) { it }, LinearOp.EQ, 8) to
                Linear(LongArray(16) { 1L }, IntArray(16) { it + 15 }, LinearOp.LE, 8),
        )
        for ((left, right) in sources) {
            val problem = Problem(0, 31, Array(31) { IntDomain(0, 1) }, listOf(left, right))

            val neighborhood = IntegerLearningNeighborhood.inspect(problem)

            assertFalse(neighborhood.compatible)
        }
    }

    @Test
    fun `an exhausted probe should withhold a later compatible neighbor`() {
        val equality = Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 1)
        val factors = mutableListOf<Factor>(equality)
        repeat(32) { offset ->
            factors.add(Linear(LongArray(16) { 1L }, IntArray(16) { 16 + (it + offset) % 32 }, LinearOp.LE, 8))
        }
        factors.add(Linear(longArrayOf(-1, 1), intArrayOf(2, 47), LinearOp.LE, 0))
        val problem = Problem(0, 48, Array(48) { IntDomain(0, 1) }, factors)

        val neighborhood = IntegerLearningNeighborhood.inspect(problem)

        assertTrue(neighborhood.limited)
        assertFalse(neighborhood.compatible)
    }
}
