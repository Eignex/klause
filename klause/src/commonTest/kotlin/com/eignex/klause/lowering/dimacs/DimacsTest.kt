package com.eignex.klause.lowering.dimacs

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.dimacs.DimacsFormatException
import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DimacsTest {

    private object Dimacs {
        fun parse(text: String) = com.eignex.klause.formats.dimacs.Dimacs.parse(text).toProblem()
        fun parseWcnf(text: String) = com.eignex.klause.formats.dimacs.Dimacs.parseWcnf(text).toProblem()
    }

    @Test
    fun `parses simple sat instance`() {
        val text = """
            c sample
            p cnf 3 3
            1 -2 3 0
            -1 2 0
            -3 0
        """.trimIndent()
        val problem = Dimacs.parse(text)
        assertEquals(3, problem.numBoolVars)
        assertEquals(0, problem.numIntVars)
        assertEquals(3, problem.factors.size)
        val first = problem.factors[0] as Clause
        assertEquals(
            listOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true)),
            first.literals.toList(),
        )
    }

    @Test
    fun `accepts multi line clauses and comments`() {
        val text = """
            c first comment
            % alt comment
            p cnf 2 2
            1 2
            0
            -1 -2 0
        """.trimIndent()
        val problem = Dimacs.parse(text)
        assertEquals(2, problem.numBoolVars)
        assertEquals(2, problem.factors.size)
    }

    @Test
    fun `rejects malformed cnf input`() {
        val malformed = listOf(
            "1 2 3 0\n", // missing header
            "p cnf 2 1\n1 2 3 0\n", // literal out of range
        )
        for (text in malformed) assertFails(text) { Dimacs.parse(text) }
    }

    @Test
    fun `rejects a header whose clause count differs from the body`() {
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parse("p cnf 2 2\n1 0\n") }
        assertTrue(ex.message?.contains("declares 2 clauses, found 1") == true)
    }

    @Test
    fun `rejects a non-integer cnf variable count with a header diagnostic`() {
        // An over-Int count must surface a message naming the header field, not a bare
        // NumberFormatException from toInt().
        val e = assertFailsWith<DimacsFormatException> { Dimacs.parse("p cnf 5000000000 1\n1 0\n") }
        assertTrue("variable count" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `ignores trailing percent block`() {
        val text = """
            p cnf 2 1
            1 -2 0
            %
            0
        """.trimIndent()
        val problem = Dimacs.parse(text)
        assertTrue(problem.factors.size == 1)
    }

    @Test
    fun `parses old wcnf format with top weight`() {
        val text = """
            c sample wcnf
            p wcnf 3 3 10
            10 1 2 0
            1 -1 0
            2 -2 0
        """.trimIndent()
        val w = Dimacs.parseWcnf(text)
        assertEquals(3, w.numOriginalBoolVars)
        assertEquals(5, w.problem.numBoolVars)
        assertEquals(3, w.problem.factors.size)
        assertEquals(0L, w.objective.boolWeights[0])
        assertEquals(1L, w.objective.boolWeights[3])
        assertEquals(2L, w.objective.boolWeights[4])
    }

    @Test
    fun `old wcnf without top treats max-weight clause as hard`() {
        val text = """
            p wcnf 3 2
            9223372036854775807 1 2 0
            1 -1 0
        """.trimIndent()
        val w = Dimacs.parseWcnf(text)
        assertEquals(3, w.numOriginalBoolVars)
        assertEquals(4, w.problem.numBoolVars)
        assertEquals(2, w.problem.factors.size)
        assertEquals(1L, w.objective.boolWeights[3])
    }

    @Test
    fun `old wcnf without top keeps normal-weight clauses soft`() {
        val w = Dimacs.parseWcnf("p wcnf 2 1\n5 -1 0\n")
        assertEquals(2, w.numOriginalBoolVars)
        assertEquals(3, w.problem.numBoolVars)
        assertEquals(5L, w.objective.boolWeights[2])
    }

    @Test
    fun `wcnf rejects malformed clause syntax`() {
        val malformed = listOf(
            "p wcnf 2 1\n1 -1 0 99\n", // trailing tokens after the 0 terminator
            "p wcnf 2 1\n1 -1 -2\n", // not terminated by 0
        )
        for (text in malformed) assertFails(text) { Dimacs.parseWcnf(text) }
    }

    @Test
    fun `wcnf validates the declared clause count`() {
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parseWcnf("p wcnf 1 2 10\n1 1 0\n") }
        assertTrue(ex.message?.contains("declares 2 clauses, found 1") == true)
    }

    @Test
    fun `parses new maxsat format with h prefix`() {
        val text = """
            h 1 2 0
            5 -1 0
            3 -2 0
        """.trimIndent()
        val w = Dimacs.parseWcnf(text)
        assertEquals(2, w.numOriginalBoolVars)
        assertEquals(4, w.problem.numBoolVars)
        assertEquals(3, w.problem.factors.size)
        assertEquals(5L, w.objective.boolWeights[2])
        assertEquals(3L, w.objective.boolWeights[3])
    }

    @Test
    fun `an empty soft clause becomes a fixed objective cost`() {
        // `4 0` is an always-falsified soft clause; only `2 -1 0` needs a relaxation variable.
        val w = Dimacs.parseWcnf("h 1 0\n4 0\n2 -1 0\n")
        assertEquals(1, w.numOriginalBoolVars)
        assertEquals(2, w.problem.numBoolVars)
        assertEquals(4L, w.objective.constant)
        assertEquals(2L, w.objective.boolWeights[1])
    }

    @Test
    fun `a zero-weight soft clause is dropped`() {
        val w = Dimacs.parseWcnf("h 1 0\n0 -1 0\n")
        // No relaxation variable and no cost for the zero-weight clause.
        assertEquals(1, w.numOriginalBoolVars)
        assertEquals(1, w.problem.numBoolVars)
        assertEquals(0L, w.objective.constant)
    }

    @Test
    fun `old wcnf rejects a literal beyond the declared variable count`() {
        // The old-format header fixes nvars; a literal past it must be rejected with a clear diagnostic
        // (as the CNF path does) rather than crashing with a raw index-out-of-bounds deeper in
        // construction.
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parseWcnf("p wcnf 2 1 10\n10 1 3 0\n") }
        assertTrue(ex.message?.contains("out of range") == true, "unclear diagnostic: ${ex.message}")
    }

    @Test
    fun `rejects a negative wcnf weight instead of creating a negative relaxation cost`() {
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parseWcnf("p wcnf 1 1 10\n-1 1 0\n") }
        assertTrue("non-negative" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `rejects a non-positive wcnf top`() {
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parseWcnf("p wcnf 1 0 0\n") }
        assertTrue("top must be positive" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `an empty hard clause makes the instance unsatisfiable`() {
        // The empty hard clause forces a contradiction on a fresh marker variable.
        val w = Dimacs.parseWcnf("h 0\n2 -1 0\n")
        assertTrue(w.problem.factors.any { it is Clause })
        assertEquals(2, w.problem.factors.count { it is Clause && it.literals.size == 1 })
    }

    @Test
    fun `a bare zero empty clause makes the cnf instance unsatisfiable`() {
        // `0` with no preceding literals is the empty clause (⊥); it must force a contradiction on a fresh
        // marker variable, not be silently dropped (which would report the instance satisfiable).
        val problem = Dimacs.parse("p cnf 2 2\n1 2 0\n0\n")
        assertEquals(3, problem.numBoolVars, "a marker variable is appended for the empty clause")
        assertEquals(
            2,
            problem.factors.count { it is Clause && it.literals.size == 1 },
            "the empty clause becomes two contradictory unit clauses on the marker",
        )
    }
}
