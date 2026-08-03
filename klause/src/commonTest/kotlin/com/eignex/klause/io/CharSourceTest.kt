package com.eignex.klause.io

import kotlin.test.Test
import kotlin.test.assertEquals

/** A [CharSource] that hands out pre-split chunks, to exercise consumers across arbitrary boundaries. */
private class ChunkedSource(chunks: List<String>) : CharSource {
    private val it = chunks.iterator()
    override fun next(): String? = if (it.hasNext()) it.next() else null
}

class CharSourceTest {
    @Test
    fun `readText concatenates all chunks`() {
        assertEquals("abcdef", ChunkedSource(listOf("ab", "cd", "ef")).readText())
    }

    @Test
    fun `readText of an empty source is empty`() {
        assertEquals("", StringCharSource("").readText())
    }

    @Test
    fun `lineSequence splits lines that straddle chunk boundaries`() {
        val src = ChunkedSource(listOf("one\ntw", "o\nthr", "ee"))
        assertEquals(listOf("one", "two", "three"), src.lineSequence().toList())
    }

    @Test
    fun `lineSequence strips carriage returns including a split crlf`() {
        val src = ChunkedSource(listOf("a\r", "\nb\r\nc"))
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
