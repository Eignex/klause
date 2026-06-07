package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.backtrack.TieredVariableHeuristic
import com.eignex.klause.solver.factor.GaussianXor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlatZincXorSearchTest {

    @Test
    fun `multi-xor models gain a joint gaussian system and a rare-vars-first search recipe`() {
        // b appears in both xors (a parity-bit shape); a and c are row-local (error shape).
        val src = """
            var bool: a;
            var bool: b;
            var bool: c;
            constraint bool_xor(a, b, b);
            constraint bool_xor(c, b, b);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(1, program.problem.factors.count { it is GaussianXor })
        val params = assertNotNull(program.xorSearchParams)
        val tiers = (params.variableHeuristic as TieredVariableHeuristic).tiers
        assertEquals(1, tiers.size)
        val ordered = tiers[0].boolVars
        // b occurs in 4 literal slots, a and c once each: b must come last.
        assertEquals(assertNotNull(program.boolVarsByName["b"]), ordered.last())
        assertTrue(params.phaseSaving)
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
        assertEquals(0, program.problem.factors.count { it is GaussianXor })
        assertNull(program.xorSearchParams)
    }
}
