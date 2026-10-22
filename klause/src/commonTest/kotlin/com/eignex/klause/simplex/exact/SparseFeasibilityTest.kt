package com.eignex.klause.simplex.exact

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparseFeasibilityTest {
    @Test
    fun `interrupted repairs should preserve a resumable algebraic checkpoint`() {
        var interrupted = false
        for (cut in listOf(1, 16, 64, 256, 1024)) {
            val model = object : SparseFeasibilityModel {
                override val n = 2
                override val m = 1
                override fun rhs(row: Int) = BigFraction.ofLong(4L)
                override fun column(column: Int, visit: (Int, BigFraction) -> Unit) = visit(0, BigFraction.ONE)
            }
            var tightened = false
            val bounds = object : SparseFeasibilityBounds {
                override val size = 3
                override fun lower(column: Int) = BigFraction.ZERO
                override fun upper(column: Int) = when (column) {
                    0 -> if (tightened) BigFraction.ONE else BigFraction.ofLong(4L)
                    1 -> BigFraction.ofLong(4L)
                    else -> BigFraction.ZERO
                }
            }
            val kernel = SparseFeasibility(model)
            assertNotNull(kernel.resume(bounds, SparseFeasibilityBudget(ExactContinuationLimits(), Cancellation.Never), 100).values)
            tightened = true
            var polls = 0
            val token = Cancellation { polls++ >= cut }
            val partial = kernel.resume(bounds, SparseFeasibilityBudget(ExactContinuationLimits(), token), 100)
            interrupted = interrupted || partial.decline == ContinuationDecline.CANCELLED

            val result = kernel.resume(bounds, SparseFeasibilityBudget(ExactContinuationLimits(), Cancellation.Never), 100)

            val point = assertNotNull(result.values)
            assertTrue(point[0] >= BigFraction.ZERO && point[0] <= BigFraction.ONE)
            assertEquals(BigFraction.ofLong(4L), point[0] + point[1])
            assertEquals(BigFraction.ZERO, point[2])
            assertNull(result.ray)
        }
        assertTrue(interrupted)
    }
}
