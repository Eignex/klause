package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScipReferenceTest {
    private val cmd = listOf("docker", "run", "klause-scip")

    @Test
    fun `an optimal solution is a proven feasible optimum with the primal bound`() {
        val out = "SCIP Status        : problem is solved [optimal solution found]\n" +
            "Primal Bound       : +9.00000000000000e+00 (2 solutions)\n" +
            "Dual Bound         : +9.00000000000000e+00\nGap                : 0.00 %\n"
        val r = ScipReference.parse(out, elapsedMs = 1_200, cmd = cmd, maximize = false)
        assertEquals(true, r.feasible)
        assertTrue(r.proven)
        assertEquals(9.0, r.objective)
        assertEquals("false", r.stats["maximize"])
    }

    @Test
    fun `infeasible is a proof of infeasibility`() {
        val r = ScipReference.parse("SCIP Status        : problem is solved [infeasible]\n", 50, cmd, false)
        assertEquals(false, r.feasible)
        assertTrue(r.proven)
        assertNull(r.objective)
    }

    @Test
    fun `an incumbent under a time limit is feasible but not proven`() {
        val out = "SCIP Status        : solving was interrupted [time limit reached]\n" +
            "Primal Bound       : +2.00000000000000e+01 (5 solutions)\n"
        val r = ScipReference.parse(out, 10_000, cmd, false)
        assertEquals(true, r.feasible)
        assertFalse(r.proven)
        assertEquals(20.0, r.objective)
    }

    @Test
    fun `a time limit with no incumbent is undecided`() {
        val out = "SCIP Status        : solving was interrupted [time limit reached]\n" +
            "Primal Bound       : +1.00000000000000e+20\n"
        val r = ScipReference.parse(out, 10_000, cmd, false)
        assertNull(r.feasible)
        assertFalse(r.proven)
        assertNull(r.objective)
    }

    @Test
    fun `the model objective sense is carried in stats`() {
        val out = "SCIP Status        : problem is solved [optimal solution found]\n" +
            "Primal Bound       : +4.20000000000000e+01 (1 solutions)\n"
        val r = ScipReference.parse(out, 100, cmd, maximize = true)
        assertEquals("true", r.stats["maximize"])
        assertEquals(42.0, r.objective)
    }
}
