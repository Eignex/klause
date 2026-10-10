package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiniZincSourceValidationTest {
    @Test
    fun `candidate extraction uses the last complete multiline solution`() {
        val raw = """
            WARNING: JVM startup
            x = 1;
            ----------
            % intermediate statistics
            x = 2;
            values = array1d(1..2, [
              0.5, 2.0]);
            _objective = 1.0;
            ----------
            x = 3;
        """.trimIndent()

        assertEquals(
            "x = 2;\nvalues = array1d(1..2, [\n  0.5, 2.0]);\n_objective = 1.0;",
            MiniZincSourceValidation.candidate(raw),
        )
    }

    @Test
    fun `candidate extraction skips startup diagnostics`() {
        assertEquals("x = 1;", MiniZincSourceValidation.candidate("WARNING: startup\nx = 1;\n----------\n"))
    }

    @Test
    fun `an incomplete solution cannot be checked`() {
        assertNull(MiniZincSourceValidation.candidate("x = 1;"))
    }

    @Test
    fun `a grounded source model validates its pinned objective`() {
        val fzn = "var 1.0..1.0: z; solve maximize z;"

        assertEquals("valid", MiniZincSourceValidation.inspect(fzn, 1.0).status)
    }

    @Test
    fun `a reported objective differing from the pinned objective is rejected`() {
        val fzn = "var 1.0..1.0: z; solve maximize z;"

        assertEquals("invalid", MiniZincSourceValidation.inspect(fzn, 1.001466275659824).status)
    }

    @Test
    fun `a constant source contradiction rejects the candidate`() {
        val fzn = "constraint bool_eq(false, true); solve satisfy;"

        assertEquals("invalid", MiniZincSourceValidation.inspect(fzn, null).status)
    }

    @Test
    fun `residual variables and arithmetic cannot validate a candidate`() {
        for (fzn in listOf(
            "var 0.0..1.0: z; solve maximize z;",
            "var 1..2: x; constraint int_le(x, 2); solve satisfy;",
            "var 1.0..1.0: z; constraint float_le(z, 2.0); solve satisfy;",
        )) {
            assertEquals("unknown", MiniZincSourceValidation.inspect(fzn, null).status)
        }
    }

    @Test
    fun `retained witness keeps exact coordinates only from the last complete solution`() {
        val raw = """
            x = 0.5;
            % klause-exact: x = 1/2;
            ----------
            x = 0.3333333333333333;
            % klause-exact: x = 1/3;
            ----------
            x = 0.25;
            % klause-exact: x = 1/4;
        """.trimIndent()

        assertEquals("x = 1/3;", MiniZincSourceValidation.exactCoordinates(raw))
        assertEquals("x = 0.3333333333333333;\n% klause-exact: x = 1/3;", MiniZincSourceValidation.retainedWitness(raw))
    }

    @Test
    fun `pinned integer objective remains exact above binary64 consecutive integers`() {
        val fzn = "var 9007199254740993..9007199254740993: z; solve minimize z;"

        assertEquals("valid", MiniZincSourceValidation.inspect(fzn, null, "9007199254740993").status)
        assertEquals("invalid", MiniZincSourceValidation.inspect(fzn, null, "9007199254740992").status)
    }

    @Test
    fun `optimization checking without a reported objective remains unknown`() {
        val fzn = "var 1..1: z; solve minimize z;"

        assertEquals("unknown", MiniZincSourceValidation.inspect(fzn, null).status)
    }
}
