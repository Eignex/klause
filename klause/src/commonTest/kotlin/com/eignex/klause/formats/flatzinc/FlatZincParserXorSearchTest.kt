package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.bool.Xor
import kotlin.test.Test
import kotlin.test.assertEquals

class FlatZincParserXorSearchTest {

    @Test
    fun `multi-xor models stay as plain xor factors`() {
        val src = """
            var bool: a;
            var bool: b;
            var bool: c;
            constraint bool_xor(a, b, b);
            constraint bool_xor(c, b, b);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(2, program.problem.factors.count { it is Xor })
    }

    @Test
    fun `single-xor models stay untouched`() {
        val src = """
            var bool: a;
            var bool: b;
            var bool: c;
            constraint bool_xor(a, b, c);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(1, program.problem.factors.count { it is Xor })
    }
}
