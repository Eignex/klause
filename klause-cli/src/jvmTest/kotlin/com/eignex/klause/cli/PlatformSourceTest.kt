package com.eignex.klause.cli

import com.eignex.klause.util.CharSource
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PlatformSourceTest(private val failure: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "failure={0}")
        fun outcomes(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
    }

    @Test
    fun `file sources should close when parsing stops before end of input`() {
        var closed = false
        val source = object : CharSource, AutoCloseable {
            override fun next(): String = "unread input"
            override fun close() { closed = true }
        }

        if (failure) {
            assertFailsWith<IllegalArgumentException> {
                source.useSource { throw IllegalArgumentException("parse failure") }
            }
        } else {
            assertEquals("parsed", source.useSource { "parsed" })
        }

        assertTrue(closed)
    }
}
