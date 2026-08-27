package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #40: the activity-managed global cut pool — dedup, cap, and tightness-based eviction. */
class CutPoolTest {

    private fun cut(col: Int, coeff: Long, rhs: Long) =
        Cut(intArrayOf(col), longArrayOf(coeff), Relation.LE, rhs, global = true)

    @Test
    fun `deduplicates by key`() {
        val pool = CutPool()
        assertTrue(pool.add(cut(0, 1, 5)))
        assertFalse(pool.add(cut(0, 1, 5)), "an equal cut must not be added twice")
        assertEquals(1, pool.size)
    }

    @Test
    fun `keeps insertion order below the cap`() {
        val pool = CutPool(maxCuts = 8)
        val added = pool.addAll(listOf(cut(0, 1, 5), cut(1, 1, 6), cut(2, 1, 7)))
        assertEquals(3, added)
        // No eviction triggered: order and contents are exactly as inserted (behaviour-neutral).
        pool.observe(doubleArrayOf(0.0, 0.0, 0.0))
        pool.retainMostActive()
        assertEquals(listOf(5L, 6L, 7L), pool.cuts().map { it.rhs })
    }

    @Test
    fun `evicts the least active cuts when over the cap`() {
        // Three cuts x_c ≤ rhs against the LP point (x0,x1,x2) = (5, 1, 0):
        //   cut 0: slack |5 − 5| = 0   (tight — most active)
        //   cut 1: slack |6 − 1| = 5
        //   cut 2: slack |7 − 0| = 7   (loosest — least active)
        val pool = CutPool(maxCuts = 2)
        pool.addAll(listOf(cut(0, 1, 5), cut(1, 1, 6), cut(2, 1, 7)))
        pool.observe(doubleArrayOf(5.0, 1.0, 0.0))
        pool.retainMostActive()
        assertEquals(2, pool.size)
        val kept = pool.cuts().map { it.rhs }.toSet()
        assertEquals(setOf(5L, 6L), kept, "the two tightest cuts survive; the loosest is evicted")
    }

    @Test
    fun `a re-added evicted cut is accepted again`() {
        // Eviction rebuilds the dedup set, so a cut dropped for inactivity can re-enter a later harvest.
        val pool = CutPool(maxCuts = 1)
        pool.addAll(listOf(cut(0, 1, 5), cut(1, 1, 6)))
        pool.observe(doubleArrayOf(5.0, 0.0)) // keeps cut 0 (tight), evicts cut 1
        pool.retainMostActive()
        assertEquals(setOf(5L), pool.cuts().map { it.rhs }.toSet())
        assertTrue(pool.add(cut(1, 1, 6)), "the evicted cut is no longer marked seen")
    }

    @Test
    fun `decayed activity retains cuts active across observations`() {
        val pool = CutPool(maxCuts = 1)
        pool.addAll(listOf(cut(0, 1, 5), cut(1, 1, 5)))

        repeat(3) { pool.observe(doubleArrayOf(5.0, 0.0)) }
        pool.observe(doubleArrayOf(0.0, 5.0))
        pool.retainMostActive()

        assertEquals(listOf(0), pool.cuts().map { it.cols.single() })
    }

    @Test
    fun `active observation resets consecutive inactivity`() {
        val pool = CutPool(maxConsecutiveInactive = 2)
        pool.add(cut(0, 1, 5))

        pool.observe(doubleArrayOf(0.0))
        pool.observe(doubleArrayOf(5.0))
        pool.observe(doubleArrayOf(0.0))

        assertEquals(1, pool.size)
    }

    @Test
    fun `evicts a cut at the consecutive inactivity threshold`() {
        val pool = CutPool(maxConsecutiveInactive = 2)
        val stale = cut(0, 1, 5)
        pool.add(stale)

        pool.observe(doubleArrayOf(0.0))
        assertEquals(1, pool.size)
        pool.observe(doubleArrayOf(0.0))

        assertEquals(0, pool.size)
        assertTrue(pool.add(stale), "an inactive eviction clears the dedup key")
    }
}
