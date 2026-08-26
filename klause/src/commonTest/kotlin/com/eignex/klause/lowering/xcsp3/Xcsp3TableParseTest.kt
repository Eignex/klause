package com.eignex.klause.lowering.xcsp3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tuple-text parsing into [ShortRows] — the ingest step whose transients dominated peak memory on a
 * multi-MB table (issue #1415), so the arrays it produces are asserted to be exact and unduplicated as
 * well as correct.
 */
class Xcsp3TableParseTest {

    private fun rows(text: String, arity: Int): ShortRows = Xcsp3.Builder().parseShortRows(text, arity)

    @Test
    fun `a ground table carries one array for both cell bounds`() {
        // Every cell is a point, so the upper bounds equal the lower ones and a second array would be a
        // full duplicate of the payload — which buildSupportTemplate discards anyway.
        val r = rows("(0,1)(2,3)", 2)

        assertFalse(r.short, "a table with no interval cell is not short-support")
        assertSame(r.lo, r.hi, "a ground table must not allocate a second bounds array")
        assertEquals(listOf(0L, 1L, 2L, 3L), r.lo.toList())
    }

    @Test
    fun `an interval cell gives the table distinct bounds arrays`() {
        val r = rows("(0,1)(2,4..7)(*,3)", 2)

        assertTrue(r.short, "an interval cell makes the table short-support")
        assertEquals(listOf(0L, 1L, 2L, 4L, Long.MIN_VALUE, 3L), r.lo.toList())
        assertEquals(listOf(0L, 1L, 2L, 7L, Long.MAX_VALUE, 3L), r.hi.toList())
    }

    @Test
    fun `points read before the first interval keep their bounds when the upper array appears`() {
        // The upper-bound array is created only when an interval is first seen, so the points already
        // parsed have to be back-filled into it.
        val r = rows("(0,1)(2,3)(4,5..9)", 2)

        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), r.lo.toList())
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 9L), r.hi.toList())
    }

    @Test
    fun `the cell arrays are sized to exactly the tuples written`() {
        // Sized from a counting pass rather than grown, so there is no slack to trim.
        assertEquals(6, rows("(0,1,2)(3,4,5)", 3).lo.size)
        assertEquals(4, rows(" 7 8 9 10 ", 1).lo.size, "the bare unary form counts tokens")
    }
}
