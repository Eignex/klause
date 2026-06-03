package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for [IntArrayList.lowerBound] / [IntArrayList.lowerBoundDescending] — the
 * O(log n) replacements for the linear monotone-history scans in
 * [com.eignex.klause.solver.propagation.PropagationState.minLevelForGe] /
 * `maxLevelForLe` (#97). Each is checked against a brute-force linear lower bound over
 * randomised sorted inputs so the binary search provably matches the scan it replaced.
 */
class IntArrayListBinarySearchTest {

    private fun listOfInts(vararg v: Int): IntArrayList = IntArrayList().apply { v.forEach { add(it) } }

    /** Brute-force "first index with value >= element" over an ascending list. */
    private fun linearLowerBound(list: IntArrayList, element: Int): Int {
        for (i in 0 until list.size) if (list[i] >= element) return i
        return list.size
    }

    /** Brute-force "first index with value <= element" over a descending list. */
    private fun linearLowerBoundDescending(list: IntArrayList, element: Int): Int {
        for (i in 0 until list.size) if (list[i] <= element) return i
        return list.size
    }

    @Test
    fun `lowerBound matches the linear scan across edge cases`() {
        // Strictly ascending — the monotone-history invariant lowerBound is specified for.
        val ascending = listOfInts(2, 4, 6, 9, 13, 15)
        for (k in -2..18) {
            assertEquals(linearLowerBound(ascending, k), ascending.lowerBound(k), "lowerBound($k)")
        }
    }

    @Test
    fun `lowerBound handles empty and singleton lists`() {
        assertEquals(0, IntArrayList().lowerBound(5))
        assertEquals(0, listOfInts(5).lowerBound(5), "exact match at index 0")
        assertEquals(0, listOfInts(5).lowerBound(4), "below the only element")
        assertEquals(1, listOfInts(5).lowerBound(6), "above the only element → size")
    }

    @Test
    fun `lowerBoundDescending matches the linear scan across edge cases`() {
        val descending = listOfInts(15, 9, 9, 9, 6, 4, 4, 2)
        for (k in -2..18) {
            assertEquals(
                linearLowerBoundDescending(descending, k),
                descending.lowerBoundDescending(k),
                "lowerBoundDescending($k)",
            )
        }
    }

    @Test
    fun `lowerBoundDescending handles empty and singleton lists`() {
        assertEquals(0, IntArrayList().lowerBoundDescending(5))
        assertEquals(0, listOfInts(5).lowerBoundDescending(5), "exact match at index 0")
        assertEquals(0, listOfInts(5).lowerBoundDescending(6), "above the only element")
        assertEquals(1, listOfInts(5).lowerBoundDescending(4), "below the only element → size")
    }

    @Test
    fun `lowerBound respects the live size not the backing capacity`() {
        // Grow then truncate so stale data sits past `size`; the search must ignore it.
        val list = listOfInts(1, 3, 5, 7, 9)
        list.truncateTo(3) // live = [1, 3, 5]
        assertEquals(3, list.lowerBound(6), "stale 7/9 beyond size must not be found")
        assertEquals(2, list.lowerBound(5))
    }
}
