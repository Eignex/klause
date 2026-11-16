package com.eignex.klause.formats.flatzinc

import kotlin.test.Test
import kotlin.test.assertEquals

class FlatZincLexerTest {

    @Test
    fun `a string literal should keep its escapes`() {
        val tokens = FlatZincLexer("\"hello \\\"world\\\"\\n\"").tokenize().dropLast(1)
        assertEquals("hello \"world\"\n", (tokens[0] as FznToken.StringLit).value)
    }
}
