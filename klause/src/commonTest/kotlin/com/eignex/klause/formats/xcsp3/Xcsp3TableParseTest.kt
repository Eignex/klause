package com.eignex.klause.formats.xcsp3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Xcsp3TableParseTest {

    private fun rows(text: String, arity: Int): ShortRows = Compiler.Builder().parseShortRows(text, arity)

    @Test
    fun `a ground table parses point bounds`() {
        val r = rows("(0,1)(2,3)", 2)

        assertFalse(r.short, "a table with no interval cell is not short-support")
        assertEquals(listOf(0L, 1L, 2L, 3L), r.lo.toList())
        assertEquals(listOf(0L, 1L, 2L, 3L), r.hi.toList())
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
        val r = rows("(0,1)(2,3)(4,5..9)", 2)

        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), r.lo.toList())
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 9L), r.hi.toList())
    }

    @Test
    fun `tuple and unary tables preserve every parsed value`() {
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), rows("(0,1,2)(3,4,5)", 3).lo.toList())
        assertEquals(listOf(7L, 8L, 9L, 10L), rows(" 7 8 9 10 ", 1).lo.toList())
    }
}
