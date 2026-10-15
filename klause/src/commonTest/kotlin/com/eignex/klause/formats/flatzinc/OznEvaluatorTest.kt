package com.eignex.klause.formats.flatzinc

import com.eignex.klause.formats.minizinc.OznLexer
import com.eignex.klause.formats.minizinc.OznParseException
import com.eignex.klause.formats.minizinc.OznParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OznEvaluatorTest {
    private fun render(src: String): String =
        OznEvaluator(OznParser(OznLexer(src).tokenize()).parse()).render(emptyMap())

    @Test
    fun `slash should render float division not integer division`() {
        val out = render("output [show(3 / 2)];")
        assertTrue("1.5" in out, out)
    }

    @Test
    fun `range should bind looser than arithmetic in a generator source`() {
        val out = render("output [show([i | i in 1..1+2])];")
        assertTrue("[1, 2, 3]" in out, out)
    }

    @Test
    fun `conjunction should bind tighter than disjunction`() {
        val out = render("output [show(true \\/ false /\\ false)];")
        assertTrue("true" in out, out)
    }

    @Test
    fun `a multi-name array comprehension should be a cartesian product`() {
        val out = render("output [show([i + j | i, j in [10, 20]])];")
        assertTrue("[20, 30, 30, 40]" in out, out)
    }

    @Test
    fun `set rendering should preserve signed 64 bit elements`() {
        val cases = listOf(
            "{2147483648}" to "{2147483648}",
            "{-2147483649, 2147483648, -2147483649}" to "{-2147483649, 2147483648}",
            "{9223372036854775807, -9223372036854775807 - 1}" to
                "{-9223372036854775808, 9223372036854775807}",
            "{2147483647..2147483648}" to "{2147483647, 2147483648}",
            "{9223372036854775806..9223372036854775807}" to "{9223372036854775806, 9223372036854775807}",
            "{i | i in [-2147483649, 2147483648]}" to "{-2147483649, 2147483648}",
        )
        for ((expression, expected) in cases) {
            val out = render("output [show($expression)];")
            assertEquals("$expected\n----------\n", out, expression)
        }
    }

    @Test
    fun `membership should compare exact 64 bit values`() {
        val cases = listOf(
            "4294967297 in {1}" to false,
            "4294967297 in 1..2" to false,
            "2147483648 in {2147483648}" to true,
            "2147483648 in 2147483647..2147483648" to true,
            "-2147483649 in {-2147483649}" to true,
            "9223372036854775807 in (-9223372036854775807 - 1)..9223372036854775807" to true,
        )
        for ((expression, expected) in cases) {
            val out = render("output [show($expression)];")
            assertEquals("$expected\n----------\n", out, expression)
        }
    }

    @Test
    fun `range comprehension should preserve values across integer boundaries`() {
        val cases = listOf(
            "2147483647..2147483648" to "[2147483647, 2147483648]",
            "-2147483649..-2147483648" to "[-2147483649, -2147483648]",
            "9223372036854775806..9223372036854775807" to "[9223372036854775806, 9223372036854775807]",
            "(-9223372036854775807 - 1)..-9223372036854775807" to "[-9223372036854775808, -9223372036854775807]",
            "2147483648..2147483647" to "[]",
        )
        for ((range, expected) in cases) {
            val out = render("output [show([i | i in $range])];")
            assertEquals("$expected\n----------\n", out, range)
        }
    }

    @Test
    fun `one dimensional constructors should preserve explicit index sets`() {
        val cases = listOf(
            "array1d(0..1, [10,20])[1]" to 20,
            "array1d(5..6, [10,20])[5]" to 10,
            "array1d({6,5}, [10,20])[6]" to 20,
            "array1d(-2147483649..-2147483648, [10,20])[-2147483649]" to 10,
            "array1d(9223372036854775806..9223372036854775807, [10,20])[9223372036854775807]" to 20,
        )
        for ((expression, expected) in cases) {
            val out = render("output [show($expression)];")
            assertEquals("$expected\n----------\n", out, expression)
        }
    }

    @Test
    fun `constructors should preserve row major coordinates through six dimensions`() {
        for (dimensions in 2..6) {
            val axes = List(dimensions) { "2147483648..2147483649" }.joinToString(", ")
            val elements = (1..(1 shl dimensions)).joinToString(", ")
            val indices = List(dimensions) { if (it == dimensions - 1) "2147483648" else "2147483649" }
                .joinToString(", ")
            val expression = "array${dimensions}d($axes, [$elements])[$indices]"

            val out = render("output [show($expression)];")

            assertEquals("${(1 shl dimensions) - 1}\n----------\n", out, expression)
        }
    }

    @Test
    fun `one argument array coercion should rebase shaped inputs in row major order`() {
        val cases = listOf(
            "array1d(array1d(0..1, [10,20]))[1]" to "10",
            "array1d(array2d(-1..0,5..6,[10,20,30,40]))[3]" to "30",
            "array2d(0..1,0..1,array3d(1..1,1..2,1..2,[10,20,30,40]))[1,0]" to "30",
            "[i | i in array2d(0..1,0..1,[10,20,30,40])]" to "[10, 20, 30, 40]",
        )
        for ((expression, expected) in cases) {
            val out = render("output [show($expression)];")
            assertEquals("$expected\n----------\n", out, expression)
        }
    }

    @Test
    fun `array concatenation should rebase explicit index sets`() {
        val out = render("output [show((array1d(5..6,[10,20]) ++ array1d(-2..-1,[30,40]))[3])];")

        assertEquals("30\n----------\n", out)
    }

    @Test
    fun `array constructors should accept empty axes with empty elements`() {
        for (dimensions in 1..6) {
            val axes = List(dimensions) { if (it == 0) "{}" else "1..2" }.joinToString(", ")
            val out = render("output [show(array1d(array${dimensions}d($axes, [])))];")
            assertEquals("[]\n----------\n", out)
        }
    }

    @Test
    fun `shaped arrays should retain rendering and numeric reductions`() {
        val cases = listOf(
            "show2d(array2d(0..1,5..6,[10,20,30,40]))" to "[|10, 20|30, 40|]",
            "show3d(array3d(1..1,1..2,1..2,[10,20,30,40]))" to "[10, 20, 30, 40]",
            "show(sum(array4d(1..1,1..1,1..1,1..2,[10,20])))" to "30",
            "show(product(array5d(1..1,1..1,1..1,1..1,1..2,[10,20])))" to "200",
            "show(min(array6d(1..1,1..1,1..1,1..1,1..1,1..2,[10,20])))" to "10",
        )
        for ((expression, expected) in cases) {
            val out = render("output [$expression];")
            assertEquals("$expected\n----------\n", out, expression)
        }
    }

    @Test
    fun `comprehension generator variables should not leak into later output`() {
        assertFailsWith<OznParseException> { render("output [show([i | i in 1..3]), show(i)];") }
    }

    @Test
    fun `division by zero should be reported as an evaluation error`() {
        for (operator in listOf("div", "mod")) {
            assertFailsWith<OznParseException> { render("output [show(4 $operator 0)];") }
        }
    }

    @Test
    fun `an over-range integer literal should be a parse error`() {
        assertFailsWith<OznParseException> { render("output [show(99999999999999999999)];") }
    }

    @Test
    fun `out of bounds subscripts should not alias valid elements`() {
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
    fun `array subscripts should require exactly the declared dimensions`() {
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
    fun `array constructors should require matching element cardinalities`() {
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
    fun `malformed array constructors and subscripts should report evaluation errors`() {
        val expressions = listOf(
            "array1d()",
            "array2d(1..1,[10])",
            "array3d(1..1,1..1,1..1,[10], [20])",
            "array1d(1..1,10)",
            "array1d(1,[10])",
            "array1d({1,3},[10,20])",
            "[10][true]",
            "10[1]",
        )
        for (expression in expressions) {
            assertFailsWith<OznParseException> { render("output [show($expression)];") }
        }
    }

    @Test
    fun `oversized ranges and overflowing array shapes should fail before materialization`() {
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
    fun `integer arithmetic overflow should report an evaluation error`() {
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
