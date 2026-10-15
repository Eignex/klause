package com.eignex.klause.formats.minizinc

import com.eignex.klause.formats.flatzinc.OznEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OznParserTest {
    @Test
    fun `a generated output let should render without a trailing declaration separator`() {
        val source = """
            output let {array [int] of string: parts = ["answer = ", show(answer), ";\n"]} in (parts);
            int: answer = 7;
        """.trimIndent()

        val items = OznParser(OznLexer(source).tokenize()).parse()

        assertEquals("answer = 7;\n----------\n", OznEvaluator(items).render(emptyMap()))
    }

    @Test
    fun `local declarations should accept semicolon and comma separators with an optional trailing one`() {
        for (separators in listOf(";" to "", "," to "", ";" to ";", "," to ",", ";" to ",", "," to ";")) {
            val (between, trailing) = separators
            val source = "output let {int: x = 3$between int: y = x + 4$trailing} in [show(y)];"

            val items = OznParser(OznLexer(source).tokenize()).parse()

            assertEquals("7\n----------\n", OznEvaluator(items).render(emptyMap()), source)
        }
    }

    @Test
    fun `nested lets should keep their declaration separators within their own scope`() {
        val source = """
            int: result = let {
                int: x = let {int: a = 3, int: b = 4} in a + b;
                int: y = 2
            } in x * y;
            output [show(result)];
        """.trimIndent()

        val items = OznParser(OznLexer(source).tokenize()).parse()

        assertEquals("14\n----------\n", OznEvaluator(items).render(emptyMap()))
    }

    @Test
    fun `top level items should require semicolon terminators`() {
        for (source in listOf("int: x = 1", "int: x = 1,", "int: x = 1 output [show(x)];", "output [\"x\"]")) {
            assertFailsWith<OznParseException>(source) {
                OznParser(OznLexer(source).tokenize()).parse()
            }
        }
    }

    @Test
    fun `local declarations should require exactly one separator between items`() {
        for (between in listOf("", ";;", ",,", ";,", ",;")) {
            val source = "output let {int: x = 3$between int: y = 4} in [show(x + y)];"

            assertFailsWith<OznParseException>(source) {
                OznParser(OznLexer(source).tokenize()).parse()
            }
        }
    }

    @Test
    fun `malformed and truncated lets should be rejected`() {
        for (source in listOf(
            "output let {int: x = } in [show(x)];",
            "output let {int: x = 3;",
            "output let {int: x = 3",
            "output let {int: x = 3} [show(x)];",
            "output let {int: x = 3} in",
            "output let {int: x = 3} in [show(x)]",
        )) {
            assertFailsWith<OznParseException>(source) {
                OznParser(OznLexer(source).tokenize()).parse()
            }
        }
    }

    @Test
    fun `a parameter declaration should parse as a declaration`() {
        val items = OznParser(OznLexer("par int: size = 2;").tokenize()).parse()
        assertEquals(listOf(OznItem.VarDecl("size", OznExpr.IntLit(2))), items)
    }

    @Test
    fun `duplicate declarations should be rejected`() {
        assertFailsWith<OznParseException> {
            OznParser(OznLexer("par int: x = 1; par int: x = 2;").tokenize()).parse()
        }
    }

    @Test
    fun `a second output item should be rejected`() {
        assertFailsWith<OznParseException> {
            OznParser(OznLexer("output [\"a\"]; output [\"b\"];").tokenize()).parse()
        }
    }

    @Test
    fun `a nonfinite float literal should be rejected before evaluation`() {
        assertFailsWith<OznParseException> {
            OznParser(OznLexer("output [show(1e999)];").tokenize()).parse()
        }
    }
}
