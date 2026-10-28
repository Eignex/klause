package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LubyTest {

    @Test
    fun `indexed sequence should reach the largest positive Long indices`() {
        val cases = listOf(
            (1L shl 62) - 1 to (1L shl 61),
            (1L shl 62) to 1L,
            (1L shl 62) + 1 to 1L,
            Long.MAX_VALUE - 1 to (1L shl 61),
            Long.MAX_VALUE to (1L shl 62),
        )

        for ((index, expected) in cases) assertEquals(expected, lubyN(index))
    }

    @Test
    fun `indexed sequence should reject nonpositive indices`() {
        for (index in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { lubyN(index) }
        }
    }

    @Test
    fun `lubyN should yield the canonical prefix`() {
        val expected = listOf(1L, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8)
        assertEquals(expected, (1..15).map { lubyN(it.toLong()) })
    }

    @Test
    fun `iterator should yield the same sequence as the indexed form`() {
        val it = LubyIterator()
        for (i in 1..256) {
            assertEquals(lubyN(i.toLong()), it.value.toLong(), "term $i")
            it.advance()
        }
    }
}
