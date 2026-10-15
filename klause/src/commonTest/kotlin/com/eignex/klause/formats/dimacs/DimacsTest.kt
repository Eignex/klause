package com.eignex.klause.formats.dimacs

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.perCharSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DimacsTest {

    private object Dimacs {
        fun parse(text: String) = com.eignex.klause.formats.dimacs.Dimacs.parse(text).toProblem()
        fun parseWcnf(text: String) = com.eignex.klause.formats.dimacs.Dimacs.parseWcnf(text).toProblem()
    }

    @Test
    fun `cnf parsing should accept multi line clauses and comments`() {
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
    fun `malformed cnf input should be rejected`() {
        val malformed = listOf(
            "1 2 3 0\n", // missing header
            "p cnf 2 1\n1 2 3 0\n", // literal out of range
        )
        for (text in malformed) assertFails(text) { Dimacs.parse(text) }
    }

    @Test
    fun `a header whose clause count differs from the body should be rejected`() {
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parse("p cnf 2 2\n1 0\n") }
        assertTrue(ex.message?.contains("declares 2 clauses, found 1") == true)
    }

    @Test
    fun `cnf parsing should ignore a trailing percent block`() {
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
    fun `old wcnf format with top weight should parse`() {
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
        assertIs<PropagationResult.Unsat>(
            w.problem.propagate(Assumptions(bools = mapOf(0 to false, 1 to false))),
        )
        assertEquals(0L, w.objective.boolWeights[0])
        assertEquals(1L, w.objective.boolWeights[3])
        assertEquals(2L, w.objective.boolWeights[4])
    }

    @Test
    fun `old wcnf without top should treat the max-weight clause as hard`() {
        val text = """
            p wcnf 3 2
            9223372036854775807 1 2 0
            1 -1 0
        """.trimIndent()
        val w = Dimacs.parseWcnf(text)
        assertEquals(3, w.numOriginalBoolVars)
        assertEquals(w.numOriginalBoolVars, w.problem.numBoolVars)
        assertEquals(1, w.problem.factors.size)
        assertEquals(1L, w.objective.boolWeights[0])
    }

    @Test
    fun `wcnf should reject malformed clause syntax`() {
        val malformed = listOf(
            "p wcnf 2 1\n1 -1 0 99\n", // trailing tokens after the 0 terminator
            "p wcnf 2 1\n1 -1 -2\n", // not terminated by 0
        )
        for (text in malformed) assertFails(text) { Dimacs.parseWcnf(text) }
    }

    @Test
    fun `wcnf should validate the declared clause count`() {
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parseWcnf("p wcnf 1 2 10\n1 1 0\n") }
        assertTrue(ex.message?.contains("declares 2 clauses, found 1") == true)
    }

    @Test
    fun `new maxsat format with h prefix should parse`() {
        val text = """
            h 1 2 0
            5 -1 0
            3 -2 0
        """.trimIndent()
        val w = Dimacs.parseWcnf(text)
        assertEquals(2, w.numOriginalBoolVars)
        assertEquals(4, w.problem.numBoolVars)
        assertIs<PropagationResult.Unsat>(
            w.problem.propagate(Assumptions(bools = mapOf(0 to false, 1 to false))),
        )
        assertEquals(5L, w.objective.boolWeights[2])
        assertEquals(3L, w.objective.boolWeights[3])
    }

    @Test
    fun `an empty soft clause should become a fixed objective cost`() {
        val w = Dimacs.parseWcnf("h 1 0\n4 0\n2 -1 0\n")
        assertEquals(1, w.numOriginalBoolVars)
        assertEquals(1, w.problem.numBoolVars)
        assertEquals(4L, w.objective.constant)
        assertEquals(2L, w.objective.boolWeights[0])
    }

    @Test
    fun `empty soft clauses should preserve the largest representable fixed cost`() {
        val w = Dimacs.parseWcnf("4611686018427387904 0\n4611686018427387903 0\n")

        assertEquals(Long.MAX_VALUE, w.objective.constant)
    }

    @Test
    fun `overflowing empty soft clause costs should be rejected`() {
        val ex = assertFailsWith<DimacsFormatException> {
            Dimacs.parseWcnf("4611686018427387904 0\n4611686018427387904 0\n")
        }

        assertTrue("fixed objective cost" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `headerless wcnf should reject a literal whose magnitude exceeds the index range`() {
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parseWcnf("1 -2147483648 0\n") }

        assertTrue("literal index" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `a zero-weight soft clause should be dropped`() {
        val w = Dimacs.parseWcnf("h 1 0\n0 -1 0\n")
        assertEquals(1, w.numOriginalBoolVars)
        assertEquals(1, w.problem.numBoolVars)
        assertEquals(0L, w.objective.constant)
    }

    @Test
    fun `old wcnf should reject a literal beyond the declared variable count`() {
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parseWcnf("p wcnf 2 1 10\n10 1 3 0\n") }
        assertTrue(ex.message?.contains("out of range") == true, "unclear diagnostic: ${ex.message}")
    }

    @Test
    fun `a negative wcnf weight should be rejected`() {
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parseWcnf("p wcnf 1 1 10\n-1 1 0\n") }
        assertTrue("non-negative" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `a non-positive wcnf top should be rejected`() {
        val ex = assertFailsWith<DimacsFormatException> { Dimacs.parseWcnf("p wcnf 1 0 0\n") }
        assertTrue("top must be positive" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `an empty hard clause should make the instance unsatisfiable`() {
        val w = Dimacs.parseWcnf("h 0\n2 -1 0\n")
        assertTrue(BacktrackSolver(w.problem.bake()).solve() is SolveResult.Unsat)
    }

    @Test
    fun `parse should not depend on source chunk boundaries`() {
        val text = "c comment\r\np cnf 3 2\n1 -2 0\n2 3 0\n"
        fun shape(source: CharSource) = com.eignex.klause.formats.dimacs.Dimacs.parse(source).toProblem()
            .let { Triple(it.numBoolVars, it.numIntVars, it.factors.size) }
        assertEquals(shape(StringCharSource(text)), shape(perCharSource(text)))
    }
}
