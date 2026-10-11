package com.eignex.klause.solver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SearchStreamTest {
    @Test
    fun `closing a buffered cursor discards its value and releases once`() {
        var releases = 0
        val stream = PullSearchStream(pull = { 1 }, release = { releases++ })
        assertTrue(stream.hasNext())
        stream.close()
        stream.close()
        assertFalse(stream.hasNext())
        assertTrue(stream.isDone)
        assertEquals(1, releases)
    }

    @Test
    fun `terminal values remain readable after resources release`() {
        var releases = 0
        val stream = PullSearchStream(pull = { 1 }, release = { releases++ }, terminal = { true })
        assertTrue(stream.hasNext())
        assertTrue(stream.isDone)
        assertEquals(1, releases)
        assertEquals(1, stream.next())
        assertFalse(stream.hasNext())
        stream.close()
        assertEquals(1, releases)
    }

    @Test
    fun `search failures preserve cleanup failures as suppressed`() {
        val failure = IllegalArgumentException("search")
        val cleanup = IllegalStateException("cleanup")
        val stream = PullSearchStream<Int>(pull = { throw failure }, release = { throw cleanup })
        assertSame(failure, assertFailsWith<IllegalArgumentException> { stream.hasNext() })
        assertSame(cleanup, failure.suppressedExceptions.single())
        stream.close()
        assertFalse(stream.hasNext())
    }

    @Test
    fun `search streams reject a second iterator`() {
        val stream = sequenceOf(1).asSearchStream()
        assertEquals(listOf(1), stream.toList())
        assertFailsWith<IllegalStateException> { stream.iterator() }
    }
}
