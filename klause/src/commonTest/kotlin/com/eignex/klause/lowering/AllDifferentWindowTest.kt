package com.eignex.klause.lowering

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AllDifferentWindowTest {

    private fun window(bounds: List<Pair<Long?, Long?>>) = allDifferentWindow(
        IntArray(bounds.size) { it },
        { bounds[it].first },
        { bounds[it].second },
    )

    @Test
    fun `members bounded on both sides give the window spanning them`() {
        val w = window(listOf(2L to 5L, 0L to 3L))

        assertEquals(0L, w?.min)
        assertEquals(6, w?.size)
    }

    @Test
    fun `a member open on either side has no window to index`() {
        assertNull(window(listOf(0L to 5L, 1L to null)), "open above")
        assertNull(window(listOf(0L to 5L, null to 3L)), "open below")
    }

    @Test
    fun `a window wider than an Int can address is declined`() {
        assertNull(window(listOf(0L to Long.MAX_VALUE, 0L to 1L)))
    }

    @Test
    fun `fewer than two members needs no global`() {
        assertNull(window(listOf(0L to 5L)))
    }
}
