package com.eignex.klause.formats.minizinc

import kotlin.test.Test
import kotlin.test.assertEquals

class OznLexerTest {

    @Test
    fun `each token carries the 1-based source line it starts on`() {
        // "a" on line 1; "=", "1", ";" on line 2; "x" on line 4 (line 3 is blank).
        val tokens = OznLexer("a\n= 1;\n\nx").tokenize()
        assertEquals(1, tokens.first { it.text == "a" }.line)
        assertEquals(2, tokens.first { it.text == "1" }.line)
        assertEquals(4, tokens.first { it.text == "x" }.line)
        assertEquals(4, tokens.last().line, "EOF reports the final line")
    }
}
