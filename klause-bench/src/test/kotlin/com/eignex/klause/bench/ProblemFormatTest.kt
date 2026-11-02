package com.eignex.klause.bench

import com.eignex.klause.formats.dimacs.DimacsFormatException
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProblemFormatTest {

    private fun file(suffix: String, text: String): File = createTempFile(suffix = suffix).toFile().apply {
        writeText(text)
        deleteOnExit()
    }

    @Test
    fun `dimacs metadata should not require a parseable in-process model`() {
        val source = file(".cnf", "not a DIMACS document")

        val ingested = DimacsFormat.ingest(source)

        assertNull(ingested.objective)
        assertFalse(ingested.maximize)
        assertFailsWith<DimacsFormatException> { ingested.problem }
    }

    @Test
    fun `a deferred dimacs model should retain its declared variables and clauses`() {
        val source = file(".cnf", "p cnf 2 1\n1 -2 0\n")
        val ingested = DimacsFormat.ingest(source)

        val problem = ingested.problem

        assertEquals(2, problem.numBoolVars)
        assertEquals(1, problem.factors.size)
    }

    @Test
    fun `an smt-lib maximization should report its sense even with an open column`() {
        val source = file(
            ".smt2",
            "(set-logic QF_LIA)\n(declare-const x Int)\n(assert (<= x 3))\n(maximize x)\n(check-sat)",
        )

        val ingested = SmtLibFormat.ingest(source)

        assertTrue(ingested.maximize)
        assertFailsWith<IllegalStateException> { ingested.problem }
    }
}
