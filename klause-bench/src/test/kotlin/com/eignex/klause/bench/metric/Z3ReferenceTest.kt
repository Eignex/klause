package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format

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
        assertNull(r.objective)
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
        val r = Z3Reference.parse("; a comment\nsat\n", elapsedMs = 7, cmd = cmd)
        assertEquals(true, r.feasible)
        assertTrue(r.proven)
    }

    @Test
    fun `no verdict line is undecided`() {
        val r = Z3Reference.parse("(error \"boom\")\n", elapsedMs = 3, cmd = cmd)
        assertNull(r.feasible)
        assertFalse(r.proven)
    }

    @Test
    fun `a parse error followed by sat does not earn proof credit`() {
        val r = Z3Reference.parse("(error \"unsupported assertion\")\nsat\n", 7, cmd)

        assertNull(r.feasible)
        assertFalse(r.proven)
        assertEquals("error", r.stats["referenceStatus"])
    }

    @Test
    fun `failed exits retain stderr and do not earn proof credit`() {
        val r = Z3Reference.parse("sat\n", 7, cmd, stderr = "out of memory", exitCode = 1)

        assertNull(r.feasible)
        assertEquals("out of memory", r.stats["referenceStderr"])
        assertEquals("1", r.stats["referenceExitCode"])
    }

    @Test
    fun `argument errors without stdout retain stderr and no proof`() {
        val stderr = "ERROR: invalid parameter, unknown module '/corpus/'\n"

        val r = Z3Reference.parse("", 7, cmd, stderr = stderr, exitCode = 110)

        assertNull(r.feasible)
        assertFalse(r.proven)
        assertNull(r.objective)
        assertEquals("error", r.stats["referenceStatus"])
        assertEquals("110", r.stats["referenceExitCode"])
        assertEquals(stderr, r.stats["referenceStderr"])
        assertEquals("", r.rawOutput)
    }

    @Test
    fun `watchdog termination retains its outcome`() {
        val r = Z3Reference.parse("", 30_000, cmd, exitCode = 137, killed = true)

        assertNull(r.feasible)
        assertEquals("timeout", r.stats["referenceStatus"])
        assertEquals("true", r.stats["referenceWatchdogKilled"])
    }

    @Test
    fun `native timeout is distinct from an unknown verdict`() {
        val r = Z3Reference.parse("timeout\n", 10_000, cmd)

        assertNull(r.feasible)
        assertEquals("timeout", r.stats["referenceStatus"])
    }

    @Test
    fun `algebraic model bytes survive reference parsing`() {
        val model = "(\n  (define-fun x () Real (root-obj (+ (^ x 2) (- 2)) 2))\n)"
        val stdout = "sat\n$model\n"

        val r = Z3Reference.parse(stdout, 7, cmd)

        assertEquals(stdout, r.rawOutput)
        assertEquals(model, SolveEvidence.finalWitness(Format.SMTLIB, r.rawOutput))
    }
}
