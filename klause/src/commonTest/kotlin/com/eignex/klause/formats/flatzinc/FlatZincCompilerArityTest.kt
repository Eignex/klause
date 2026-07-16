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
}
