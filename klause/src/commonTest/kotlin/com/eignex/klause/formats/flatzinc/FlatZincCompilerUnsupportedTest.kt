package com.eignex.klause.formats.flatzinc

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FlatZincCompilerUnsupportedTest {

    @Test
    fun `constructs klause does not cover are declined as unsupported`() {
        val cases = listOf(
            "var float: x;\nsolve satisfy;",
            "var 1..3: x;\nconstraint not_a_real_builtin(x);\nsolve satisfy;",
        )
        for (src in cases) assertFailsWith<UnsupportedFlatZincException>(src) { parseFlatZinc(src) }
    }

    @Test
    fun `a malformed instance is a parse error and not unsupported`() {
        val e = assertFailsWith<FlatZincParseException> {
            parseFlatZinc(
                "var bool: b;\nconstraint bool2int(b);\nsolve satisfy;",
            )
        }

        assertFalse(e is UnsupportedFlatZincException)
    }
}
