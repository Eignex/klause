package com.eignex.klause.formats.flatzinc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincLexerTest {

    @Test
    fun `tokens for var decl`() {
        val tokens = FlatZincLexer("var int: x;").tokenize().dropLast(1)
        assertEquals(5, tokens.size)
        assertEquals("var", (tokens[0] as FznToken.Kw).keyword)
        assertEquals("int", (tokens[1] as FznToken.Kw).keyword)
        assertEquals(":", (tokens[2] as FznToken.Punct).symbol)
        assertEquals("x", (tokens[3] as FznToken.Ident).name)
        assertEquals(";", (tokens[4] as FznToken.Punct).symbol)
    }

    @Test
    fun `int range token uses two-dot punct`() {
        val tokens = FlatZincLexer("1..10").tokenize().dropLast(1)
        assertEquals(3, tokens.size)
        assertEquals(1L, (tokens[0] as FznToken.IntLit).value)
        assertEquals("..", (tokens[1] as FznToken.Punct).symbol)
        assertEquals(10L, (tokens[2] as FznToken.IntLit).value)
    }

    @Test
    fun `float literal is recognized`() {
        val tokens = FlatZincLexer("3.14 -2.5e3").tokenize().dropLast(1)
        assertEquals(3.14, (tokens[0] as FznToken.FloatLit).value)
        assertEquals(-2500.0, (tokens[1] as FznToken.FloatLit).value)
    }

    @Test
    fun `negative int literal`() {
        val tokens = FlatZincLexer("-7").tokenize().dropLast(1)
        assertEquals(-7L, (tokens[0] as FznToken.IntLit).value)
    }

    @Test
    fun `string literal with escapes`() {
        val tokens = FlatZincLexer("\"hello \\\"world\\\"\\n\"").tokenize().dropLast(1)
        assertEquals("hello \"world\"\n", (tokens[0] as FznToken.StringLit).value)
    }

    @Test
    fun `line comment is skipped`() {
        val tokens = FlatZincLexer("var int: x; % a comment\nvar bool: y;").tokenize().dropLast(1)
        // Expect 10 tokens: var int : x ; var bool : y ;
        assertEquals(10, tokens.size)
        assertEquals("y", (tokens[8] as FznToken.Ident).name)
    }

    @Test
    fun `double-colon punct`() {
        val tokens = FlatZincLexer("var int: x :: output_var = 0;").tokenize().dropLast(1)
        // Find the `::` token
        assertTrue(tokens.any { it is FznToken.Punct && it.symbol == "::" })
    }

    @Test
    fun `keywords vs identifiers`() {
        val tokens = FlatZincLexer("var bool true false intersect").tokenize().dropLast(1)
        assertEquals("var", (tokens[0] as FznToken.Kw).keyword)
        assertEquals("bool", (tokens[1] as FznToken.Kw).keyword)
        assertEquals("true", (tokens[2] as FznToken.Kw).keyword)
        assertEquals("false", (tokens[3] as FznToken.Kw).keyword)
        // `intersect` isn't in our keyword list — should be an identifier.
        assertEquals("intersect", (tokens[4] as FznToken.Ident).name)
    }

    @Test
    fun `eof at end`() {
        val tokens = FlatZincLexer("").tokenize()
        assertEquals(1, tokens.size)
        assertIs<FznToken.Eof>(tokens[0])
    }
}
