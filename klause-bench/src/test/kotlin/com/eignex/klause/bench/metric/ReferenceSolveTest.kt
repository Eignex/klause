package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import com.eignex.klause.bench.runner.Budget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReferenceSolveTest {
    private val ref = ProblemRef("absent", Format.XCSP3, corpus("absent.xml"), Category.CSP, Expected.Unknown)

    @Test
    fun `default and explicit XCSP references identify cp-sat`() {
        val labels = listOf(null, "cp-sat").map { solver ->
            ReferenceSolve.solverIdFor(ref, "cp-sat", solver)
        }

        assertEquals(listOf("cp-sat", "cp-sat"), labels)
    }

    @Test
    fun `unsupported XCSP solvers are rejected before reference invocation`() {
        val errors = listOf("ace", "choco", "unknown").map { solver ->
            assertFailsWith<IllegalArgumentException> {
                ReferenceSolve.run(ref, "cp-sat", SolverInvocation.Settings(), Budget(1), solver)
            }.message
        }

        assertEquals(
            listOf("ace", "choco", "unknown").map {
                "reference solver '$it' does not solve XCSP3; it is solved by cp-sat"
            },
            errors,
        )
    }

    @Test
    fun `unsupported XCSP backends are rejected before reference invocation`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ReferenceSolve.run(ref, "choco", SolverInvocation.Settings(), Budget(1))
        }

        assertEquals("reference solver 'choco' does not solve XCSP3; it is solved by cp-sat", error.message)
    }

    @Test
    fun `unsupported XCSP solve-one requests fail before recording a verdict`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SolveMetric.solveOne(ref, Budget(1), SolveMetric.REFERENCE, referenceSolver = "ace")
        }

        assertEquals("reference solver 'ace' does not solve XCSP3; it is solved by cp-sat", error.message)
    }
}
