package com.eignex.klause.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A [CharSource] that hands out pre-split chunks, to exercise the reader across arbitrary boundaries. */
private class ChunkReader(chunks: List<String>) : CharSource {
    private val it = chunks.iterator()
    override fun next(): String? = if (it.hasNext()) it.next() else null
}

class CharReaderTest {
    private fun drain(reader: CharReader): String = buildString {
        while (!reader.eof()) {
            append(reader.peek().toChar())
            reader.advance()
        }
    }

    @Test
    fun `peek and advance read every character in order`() {
        val reader = CharReader(StringCharSource("hello"))
        assertEquals("hello", drain(reader))
    }

    @Test
    fun `peek reads across chunk boundaries`() {
        val reader = CharReader(ChunkReader(listOf("ab", "cd", "ef")))
        assertEquals("abcdef", drain(reader))
    }

    @Test
    fun `lookahead spans a chunk boundary`() {
        val reader = CharReader(ChunkReader(listOf("a", "b")))
        assertEquals('a'.code, reader.peek())
        assertEquals('b'.code, reader.peek(1))
    }

    @Test
    fun `peek past end of input returns minus one`() {
        val reader = CharReader(StringCharSource("x"))
        assertEquals('x'.code, reader.peek())
        assertEquals(-1, reader.peek(1))
    }

    @Test
    fun `eof is true only once the input is exhausted`() {
        val reader = CharReader(StringCharSource("ab"))
        assertFalse(reader.eof())
        reader.advance()
        assertFalse(reader.eof())
        reader.advance()
        assertTrue(reader.eof())
    }

    @Test
    fun `an empty source is immediately eof`() {
        assertTrue(CharReader(StringCharSource("")).eof())
    }

    @Test
    fun `reading stays correct past the compaction threshold`() {
        val n = (1 shl 16) * 3
        val reader = CharReader(ChunkReader(List(n) { "a" }))
        var count = 0
        while (!reader.eof()) {
            assertEquals('a'.code, reader.peek())
            reader.advance()
            count++
        }
        assertEquals(n, count)
    }
}
