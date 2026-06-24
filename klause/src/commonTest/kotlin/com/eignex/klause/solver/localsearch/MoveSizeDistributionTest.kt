package com.eignex.klause.solver.localsearch

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoveSizeDistributionTest {

    @Test
    fun `each size gets exactly its weighted share within a cycle`() {
        // Sizes 1, 2, 3 with shares 3, 1, 2 — a cycle is 6 draws.
        val dist = MoveSizeDistribution(weights = intArrayOf(3, 1, 2), minSize = 1)
        val rng = Random(7)
        repeat(2) { cycle ->
            val counts = HashMap<Int, Int>()
            repeat(6) {
                val s = dist.nextSize(rng)
                counts[s] = (counts[s] ?: 0) + 1
            }
            assertEquals(3, counts[1], "cycle $cycle size 1")
            assertEquals(1, counts[2], "cycle $cycle size 2")
            assertEquals(2, counts[3], "cycle $cycle size 3")
        }
    }

    @Test
    fun `sizes stay within the configured range`() {
        val dist = MoveSizeDistribution(weights = intArrayOf(1, 1, 1), minSize = 4)
        val rng = Random(1)
        repeat(100) { assertTrue(dist.nextSize(rng) in 4..6) }
    }

    @Test
    fun `a single-weight distribution always returns that size`() {
        val dist = MoveSizeDistribution(weights = intArrayOf(0, 5, 0), minSize = 1)
        val rng = Random(3)
        repeat(20) { assertEquals(2, dist.nextSize(rng)) }
    }
}
