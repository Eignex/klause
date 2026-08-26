package com.eignex.klause.lowering.flatzinc

import com.eignex.klause.factor.bool.Xor
import kotlin.test.Test
import kotlin.test.assertEquals

class FlatZincParserXorSearchTest {

    @Test
    fun `each bool_xor constraint lowers to one plain Xor factor`() {
        val cases = listOf(
            1 to "constraint bool_xor(a, b, c);",
            2 to "constraint bool_xor(a, b, b);\nconstraint bool_xor(c, b, b);",
        )
        for ((expected, constraints) in cases) {
            val src = "var bool: a;\nvar bool: b;\nvar bool: c;\n$constraints\nsolve satisfy;"
            val program = parseFlatZinc(src)
            assertEquals(expected, program.problem.factors.count { it is Xor }, constraints)
        }
    }
}
