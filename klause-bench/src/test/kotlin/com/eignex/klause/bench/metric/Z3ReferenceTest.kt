package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Z3ReferenceTest {
    private val cmd = listOf("z3", "-T:10", "problem.smt2")

    @Test
    fun `sat is a proven feasible witness with no objective`() {
        val r = Z3Reference.parse("sat\n", elapsedMs = 121, cmd = cmd)
        assertEquals(true, r.feasible)
        assertTrue(r.proven)
        assertNull(r.objective, "QF_LIA is a decision problem")
    }

    @Test
    fun `unsat is a proof of infeasibility`() {
        val r = Z3Reference.parse("unsat\n", elapsedMs = 50, cmd = cmd)
        assertEquals(false, r.feasible)
        assertTrue(r.proven)
    }

    @Test
    fun `unknown is undecided`() {
        val r = Z3Reference.parse("unknown\n", elapsedMs = 10_000, cmd = cmd)
        assertNull(r.feasible)
        assertFalse(r.proven)
    }

    @Test
    fun `the last verdict line wins and surrounding noise is ignored`() {
        // z3 may print warnings/comments before the check-sat answer; only the bare verdict counts.
        val r = Z3Reference.parse("(error \"parse note\")\n; a comment\nsat\n", elapsedMs = 7, cmd = cmd)
        assertEquals(true, r.feasible)
        assertTrue(r.proven)
    }

    @Test
    fun `no verdict line is undecided`() {
        val r = Z3Reference.parse("(error \"boom\")\n", elapsedMs = 3, cmd = cmd)
        assertNull(r.feasible)
        assertFalse(r.proven)
    }
}
