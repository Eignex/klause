package com.eignex.klause.lowering.flatzinc

import com.eignex.klause.formats.flatzinc.FlatZincParseException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlatZincCompilerArityTest {

    @Test
    fun `a semantic compile error reports the constraint's source line`() {
        // An unsupported builtin sits on line 3; the compile error must be located there, not at 0:0.
        val src = "var 1..3: x;\nvar 1..3: y;\nconstraint not_a_real_builtin(x, y);\nsolve satisfy;"
        val e = assertFailsWith<FlatZincParseException> { parseFlatZinc(src) }
        assertTrue("at 3:" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `bool2int with the wrong arity is a parse error not an index crash`() {
        // A malformed bool2int (one arg) must fail as a FlatZincParseException, not an
        // IndexOutOfBoundsException from the unchecked second argument.
        val src = "var bool: b;\nconstraint bool2int(b);\nsolve satisfy;"
        assertFailsWith<FlatZincParseException> { parseFlatZinc(src) }
    }

    @Test
    fun `float_div with the wrong arity is a parse error not an index crash`() {
        // float_div reorders its three args into a float_times before lowering; a truncated call must
        // fail as a FlatZincParseException, not an IndexOutOfBoundsException from the reorder.
        val src = "var 0.0..1.0: a;\nvar 0.0..1.0: b;\nconstraint float_div(a, b);\nsolve satisfy;"
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
