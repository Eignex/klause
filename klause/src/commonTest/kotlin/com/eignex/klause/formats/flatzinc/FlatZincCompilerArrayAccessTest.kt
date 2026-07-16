package com.eignex.klause.formats.flatzinc

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlatZincCompilerArrayAccessTest {

    @Test
    fun `an out-of-bounds parameter array access is a parse error not an index crash`() {
        val src = """
            array[1..3] of int: a = [10, 20, 30];
            var 1..9: x;
            constraint int_lin_le([1], [x], a[5]);
            solve satisfy;
        """.trimIndent()
        val e = assertFailsWith<FlatZincParseException> { parseFlatZinc(src) }
        assertTrue("out of bounds" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `an out-of-range array length is rejected rather than crashing with a negative array size`() {
        // hi overflows Int, so the old hi.toInt() produced a negative length and a
        // NegativeArraySizeException downstream.
        val src = "array[1..3000000000] of var int: a;\nsolve satisfy;"
        assertFailsWith<IllegalArgumentException> { parseFlatZinc(src) }
    }
}
