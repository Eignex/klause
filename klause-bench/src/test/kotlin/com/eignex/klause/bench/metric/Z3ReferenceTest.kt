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
    fun `sat should be a proven feasible witness with no objective`() {
        val r = Z3Reference.parse("sat\n", elapsedMs = 121, cmd = cmd)
        assertEquals(true, r.feasible)
        assertTrue(r.proven)
        assertNull(r.objective)
    }

    @Test
    fun `unsat should be a proof of infeasibility`() {
        val r = Z3Reference.parse("unsat\n", elapsedMs = 50, cmd = cmd)
        assertEquals(false, r.feasible)
        assertTrue(r.proven)
    }

    @Test
    fun `unknown should be undecided`() {
        val r = Z3Reference.parse("unknown\n", elapsedMs = 10_000, cmd = cmd)
        assertNull(r.feasible)
        assertFalse(r.proven)
    }

    @Test
    fun `the last verdict line should win over surrounding noise`() {
        val r = Z3Reference.parse("; a comment\nsat\n", elapsedMs = 7, cmd = cmd)
        assertEquals(true, r.feasible)
        assertTrue(r.proven)
    }

    @Test
    fun `a missing verdict line should be undecided`() {
        val r = Z3Reference.parse("(error \"boom\")\n", elapsedMs = 3, cmd = cmd)
        assertNull(r.feasible)
        assertFalse(r.proven)
    }

    @Test
    fun `a parse error followed by sat should not earn proof credit`() {
        val r = Z3Reference.parse("(error \"unsupported assertion\")\nsat\n", 7, cmd)

        assertNull(r.feasible)
        assertFalse(r.proven)
        assertEquals("error", r.stats["referenceStatus"])
    }

    @Test
    fun `failed exits should retain stderr and not earn proof credit`() {
        val r = Z3Reference.parse("sat\n", 7, cmd, stderr = "out of memory", exitCode = 1)

        assertNull(r.feasible)
        assertEquals("out of memory", r.stats["referenceStderr"])
        assertEquals("1", r.stats["referenceExitCode"])
    }

    @Test
    fun `watchdog termination should retain its outcome`() {
        val r = Z3Reference.parse("", 30_000, cmd, exitCode = 137, killed = true)

        assertNull(r.feasible)
        assertEquals("timeout", r.stats["referenceStatus"])
        assertEquals("true", r.stats["referenceWatchdogKilled"])
    }

    @Test
    fun `native timeout should be distinct from an unknown verdict`() {
        val r = Z3Reference.parse("timeout\n", 10_000, cmd)

        assertNull(r.feasible)
        assertEquals("timeout", r.stats["referenceStatus"])
    }

    @Test
    fun `algebraic model bytes should survive reference parsing`() {
        val model = "(\n  (define-fun x () Real (root-obj (+ (^ x 2) (- 2)) 2))\n)"
        val stdout = "sat\n$model\n"

        val r = Z3Reference.parse(stdout, 7, cmd)

        assertEquals(stdout, r.rawOutput)
        assertEquals(model, SolveEvidence.finalWitness(Format.SMTLIB, r.rawOutput))
    }
}
