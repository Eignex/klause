package com.eignex.klause.formats.flatzinc

import com.eignex.klause.formats.minizinc.OznLexer
import com.eignex.klause.formats.minizinc.OznParseException
import com.eignex.klause.formats.minizinc.OznParser
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OznEvalRobustnessTest {

    private fun render(src: String): String =
        OznEvaluator(OznParser(OznLexer(src).tokenize()).parse()).render(emptyMap())

    @Test
    fun `comprehension generator variables do not leak into later output`() {
        assertFailsWith<OznParseException> { render("output [show([i | i in 1..3]), show(i)];") }
    }

    @Test
    fun `division by zero is reported as an evaluation error`() {
        for (operator in listOf("div", "mod")) {
            assertFailsWith<OznParseException> { render("output [show(4 $operator 0)];") }
        }
    }

    @Test
    fun `an over-range integer literal is a parse error not a raw number-format exception`() {
        assertFailsWith<OznParseException> { render("output [show(99999999999999999999)];") }
    }

    @Test
    fun `out of bounds subscripts cannot alias valid elements`() {
        val expressions = listOf(
            "[10][4294967297]",
            "[10][-4294967295]",
            "[10][0]",
            "[10][2]",
            "[][1]",
            "array1d(5..6,[10,20])[1]",
            "array2d(1..2,1..2,[10,20,30,40])[1,3]",
            "array2d(1..2,1..2,[10,20,30,40])[2,0]",
            "array3d(1..2,1..2,1..2,[1,2,3,4,5,6,7,8])[1,1,3]",
            "array3d(1..2,1..2,1..2,[1,2,3,4,5,6,7,8])[1,3,1]",
        )
        for (expression in expressions) {
            val error = assertFailsWith<OznParseException> { render("output [show($expression)];") }
            assertTrue("outside axis" in error.message.orEmpty(), expression)
        }
    }

    @Test
    fun `each axis rejects subscripts outside its own bounds`() {
        for (dimensions in 1..6) {
            val axes = List(dimensions) { "0..1" }.joinToString(", ")
            val elements = (1..(1 shl dimensions)).joinToString(", ")
            for (axis in 0 until dimensions) {
                for (invalid in listOf(-1, 2)) {
                    val indices = List(dimensions) { if (it == axis) invalid else 0 }.joinToString(", ")
                    val expression = "array${dimensions}d($axes, [$elements])[$indices]"
                    val error = assertFailsWith<OznParseException> { render("output [show($expression)];") }
                    assertTrue("outside axis ${axis + 1}" in error.message.orEmpty(), expression)
                }
            }
        }
    }

    @Test
    fun `array subscripts require exactly the declared dimensions`() {
        for (dimensions in 1..6) {
            val axes = List(dimensions) { "1..1" }.joinToString(", ")
            val arities = listOf(maxOf(1, dimensions - 1), dimensions + 1).filter { it != dimensions }
            for (arity in arities) {
                val indices = List(arity) { "1" }.joinToString(", ")
                val expression = "array${dimensions}d($axes, [10])[$indices]"
                val error = assertFailsWith<OznParseException> { render("output [show($expression)];") }
                assertTrue("arity mismatch" in error.message.orEmpty(), expression)
            }
        }
    }

    @Test
    fun `array constructors require matching element cardinalities`() {
        for (dimensions in 1..6) {
            val axes = List(dimensions) { if (it == 0) "1..2" else "1..1" }.joinToString(", ")
            for (elements in listOf("[]", "[10]", "[10,20,30]")) {
                val expression = "array${dimensions}d($axes, $elements)"
                val error = assertFailsWith<OznParseException> { render("output [show($expression)];") }
                assertTrue("size mismatch" in error.message.orEmpty(), expression)
            }
        }
    }

    @Test
    fun `malformed array constructors and subscripts report evaluation errors`() {
        val expressions = listOf(
            "array1d()", "array2d(1..1,[10])", "array3d(1..1,1..1,1..1,[10], [20])",
            "array1d(1..1,10)", "array1d(1,[10])", "array1d({1,3},[10,20])",
            "[10][true]", "10[1]",
        )
        for (expression in expressions) {
            assertFailsWith<OznParseException> { render("output [show($expression)];") }
        }
    }

    @Test
    fun `oversized ranges and overflowing array shapes fail before materialization`() {
        val expressions = listOf(
            "{0..1000000}",
            "[i | i in 0..1000000]",
            "[i+j | i,j in 1..1001]",
            "{(-9223372036854775807 - 1)..9223372036854775807}",
            "array1d(0..2147483647,[])",
            "array2d(1..65536,1..65536,[])",
            "array3d(1..2147483647,1..2147483647,1..2147483647,[])",
            "array6d(1..65536,1..65536,1..65536,1..65536,1..65536,1..65536,[])",
            "show2d(array2d(1..2147483647,1..0,[]))",
        )
        for (expression in expressions) {
            assertFailsWith<OznParseException> { render("output [show($expression)];") }
        }
    }

    @Test
    fun `integer arithmetic overflow reports an evaluation error`() {
        val expressions = listOf(
            "9223372036854775807 + 1",
            "(-9223372036854775807 - 1) - 1",
            "9223372036854775807 * 2",
            "-(-9223372036854775807 - 1)",
            "abs(-9223372036854775807 - 1)",
            "(-9223372036854775807 - 1) div -1",
            "sum([9223372036854775807, 1])",
            "product([9223372036854775807, 2])",
        )
        for (expression in expressions) {
            val error = assertFailsWith<OznParseException> { render("output [show($expression)];") }
            assertTrue("overflow" in error.message.orEmpty(), expression)
        }
    }
}
