package com.eignex.klause.formats.flatzinc

import kotlin.test.Test
import kotlin.test.assertEquals

class FlatZincSetDomainTest {

    @Test
    fun `a set universe allocates one indicator per distinct value`() {
        val program = parseFlatZinc(
            """
            var set of {1, 1, 2}: s;
            solve satisfy;
            """.trimIndent(),
        )

        assertEquals(2, program.problem.numBoolVars)
        assertEquals(intArrayOf(1, 2).toList(), program.setVarsByName.getValue("s").elements.toList())
    }
}
