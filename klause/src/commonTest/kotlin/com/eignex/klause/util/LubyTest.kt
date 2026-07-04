package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals

class LubyTest {

    @Test
    fun `lubyN yields the canonical prefix`() {
        val expected = listOf(1L, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8)
        assertEquals(expected, (1..15).map { lubyN(it.toLong()) })
    }

    @Test
    fun `the iterator yields the same sequence as the indexed form`() {
        val it = LubyIterator()
        for (i in 1..256) {
            assertEquals(lubyN(i.toLong()), it.value.toLong(), "term $i")
            it.advance()
        }
    }
}
