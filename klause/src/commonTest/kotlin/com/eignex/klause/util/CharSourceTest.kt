package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals

class CharSourceTest {
    @Test
    fun `readText concatenates all chunks`() {
        assertEquals("abcdef", ChunkedCharSource(listOf("ab", "cd", "ef")).readText())
    }

    @Test
    fun `readText of an empty source is empty`() {
        assertEquals("", StringCharSource("").readText())
    }

    @Test
    fun `lineSequence splits lines that straddle chunk boundaries`() {
        val src = ChunkedCharSource(listOf("one\ntw", "o\nthr", "ee"))
        assertEquals(listOf("one", "two", "three"), src.lineSequence().toList())
    }

    @Test
    fun `lineSequence strips carriage returns including a split crlf`() {
        val src = ChunkedCharSource(listOf("a\r", "\nb\r\nc"))
        assertEquals(listOf("a", "b", "c"), src.lineSequence().toList())
    }

    @Test
    fun `lineSequence yields a final unterminated line`() {
        assertEquals(listOf("x", "y"), StringCharSource("x\ny").lineSequence().toList())
    }

    @Test
    fun `lineSequence keeps blank lines`() {
        assertEquals(listOf("a", "", "b"), StringCharSource("a\n\nb").lineSequence().toList())
    }
}
