package com.eignex.klause.formats.flatzinc

import kotlin.test.Test
import kotlin.test.assertEquals

class FlatZincLexerTest {

    @Test
    fun `an int range should lex with a two-dot punct token`() {
        val tokens = FlatZincLexer("1..10").tokenize().dropLast(1)
        assertEquals(3, tokens.size)
        assertEquals(1L, (tokens[0] as FznToken.IntLit).value)
        assertEquals("..", (tokens[1] as FznToken.Punct).symbol)
        assertEquals(10L, (tokens[2] as FznToken.IntLit).value)
    }

    @Test
    fun `a float literal should be recognized`() {
        val tokens = FlatZincLexer("3.14 -2.5e3").tokenize().dropLast(1)
        assertEquals(3.14, (tokens[0] as FznToken.FloatLit).value)
        assertEquals(-2500.0, (tokens[1] as FznToken.FloatLit).value)
    }

    @Test
    fun `a string literal should keep its escapes`() {
        val tokens = FlatZincLexer("\"hello \\\"world\\\"\\n\"").tokenize().dropLast(1)
        assertEquals("hello \"world\"\n", (tokens[0] as FznToken.StringLit).value)
    }

    @Test
    fun `a line comment should be skipped`() {
        val tokens = FlatZincLexer("var int: x; % a comment\nvar bool: y;").tokenize().dropLast(1)
        // Expect 10 tokens: var int : x ; var bool : y ;
        assertEquals(10, tokens.size)
        assertEquals("y", (tokens[8] as FznToken.Ident).name)
    }
}
