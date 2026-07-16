package com.eignex.klause.formats.flatzinc

import kotlin.test.Test
import kotlin.test.assertFailsWith

class FlatZincCompilerArityTest {

    @Test
    fun `bool2int with the wrong arity is a parse error not an index crash`() {
        // A malformed bool2int (one arg) must fail as a FlatZincParseException, not an
        // IndexOutOfBoundsException from the unchecked second argument.
        val src = "var bool: b;\nconstraint bool2int(b);\nsolve satisfy;"
        assertFailsWith<FlatZincParseException> { parseFlatZinc(src) }
    }

    @Test
    fun `constraint emitters surface a wrong arity as a parse error`() {
        // One malformed instance per constraint-emitter file: the arity guard must route
        // through the located FlatZincParseException rather than a bare require's
        // IllegalArgumentException that escapes the format error channel.
        val cases = listOf(
            "var bool: b;\nconstraint bool_eq(b);\nsolve satisfy;",
            "var 1..3: x;\nconstraint int_eq(x);\nsolve satisfy;",
            "var 1..3: x;\nconstraint all_different_int([x], [x]);\nsolve satisfy;",
            "var 0.0..1.0: f;\nconstraint float_eq(f);\nsolve satisfy;",
            "var set of 1..3: s;\nconstraint set_subset(s);\nsolve satisfy;",
            "var set of 1..3: s;\nconstraint set_card(s);\nsolve satisfy;",
        )
        for (src in cases) {
            assertFailsWith<FlatZincParseException> { parseFlatZinc(src) }
        }
    }
}
