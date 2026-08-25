package com.eignex.klause.formats.minizinc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OznLexerTest {

    @Test
    fun `an unterminated string literal is rejected`() {
        assertFailsWith<OznParseException> { OznLexer("\"abc").tokenize() }
    }

    @Test
    fun `an unterminated block comment is rejected`() {
        assertFailsWith<OznParseException> { OznLexer("/* comment").tokenize() }
    }

    @Test
    fun `an unexpected character is rejected`() {
        assertFailsWith<OznParseException> { OznLexer("x @ y").tokenize() }
    }

    @Test
    fun `each token carries the 1-based source line it starts on`() {
        // "a" on line 1; "=", "1", ";" on line 2; "x" on line 4 (line 3 is blank).
        val tokens = OznLexer("a\n= 1;\n\nx").tokenize()
        assertEquals(1, tokens.first { it.text == "a" }.line)
        assertEquals(2, tokens.first { it.text == "1" }.line)
        assertEquals(4, tokens.first { it.text == "x" }.line)
        assertEquals(4, tokens.last().line, "EOF reports the final line")
    }

    @Test
    fun `a float literal with a signed exponent lexes as one float token`() {
        val nums = OznLexer("1.5e-8").tokenize().filter {
            it.kind == OznTokenKind.FLOAT || it.kind == OznTokenKind.INT
        }
        assertEquals(1, nums.size, "expected a single numeric token")
        assertEquals(OznTokenKind.FLOAT, nums[0].kind)
        assertEquals("1.5e-8", nums[0].text)
    }

    @Test
    fun `a parameter declaration parses as a declaration`() {
        val items = OznParser(OznLexer("par int: size = 2;").tokenize()).parse()
        assertEquals(listOf(OznItem.VarDecl("size", OznExpr.IntLit(2))), items)
    }
}
