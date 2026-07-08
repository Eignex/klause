package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaspReferenceTest {
    private val cmd = listOf("docker", "run", "clasp")

    @Test
    fun `optimum found is a proven feasible optimum with the last o cost`() {
        val r = ClaspReference.parse("o 9\no 4\ns OPTIMUM FOUND\n", elapsedMs = 1_200, cmd = cmd)
        assertEquals(true, r.feasible)
        assertTrue(r.proven)
        assertEquals(4.0, r.objective)
        assertEquals("false", r.stats["maximize"], "clasp minimises OPB")
    }

    @Test
    fun `a decision satisfiable is a proven SAT witness`() {
        // No `o` lines ⇒ clasp was deciding, not optimising; a complete solver's model proves SAT.
        val r = ClaspReference.parse("s SATISFIABLE\n", elapsedMs = 300, cmd = cmd)
        assertEquals(true, r.feasible)
        assertTrue(r.proven)
        assertNull(r.objective, "a pure SAT witness carries no objective")
    }

    @Test
    fun `an optimisation incumbent is feasible but not proven optimal`() {
        // `o` lines with a non-OPTIMUM status ⇒ found an incumbent but timed out before proving optimum.
        val r = ClaspReference.parse("o 7\ns SATISFIABLE\n", elapsedMs = 10_000, cmd = cmd)
        assertEquals(true, r.feasible)
        assertFalse(r.proven)
        assertEquals(7.0, r.objective)
    }

    @Test
    fun `unsatisfiable is a proof of infeasibility`() {
        val r = ClaspReference.parse("s UNSATISFIABLE\n", elapsedMs = 50, cmd = cmd)
        assertEquals(false, r.feasible)
        assertTrue(r.proven)
    }

    @Test
    fun `unknown or timeout is undecided`() {
        val r = ClaspReference.parse("s UNKNOWN\n", elapsedMs = 10_000, cmd = cmd)
        assertNull(r.feasible)
        assertFalse(r.proven)
    }

    @Test
    fun `opb gains the problem line when absent`() {
        val opb = "* a comment\nmin: 1 x1 +2 x2 +3 x3 ;\n+1 x1 +1 x2 >= 1 ;\n+1 x2 +1 x3 >= 1 ;\n"
        val out = ClaspReference.opbWithProblemLine(opb)
        assertEquals("* #variable= 3 #constraint= 2", out.lineSequence().first())
    }

    @Test
    fun `opb with an existing problem line is unchanged`() {
        val opb = "* #variable= 2 #constraint= 1\nmin: 1 x1 +1 x2 ;\n+1 x1 +1 x2 >= 1 ;\n"
        assertEquals(opb, ClaspReference.opbWithProblemLine(opb))
    }
}
