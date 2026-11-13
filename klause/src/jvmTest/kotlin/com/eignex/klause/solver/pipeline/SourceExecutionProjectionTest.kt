package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLib
import kotlin.test.assertIs
import org.junit.Test

class SourceExecutionProjectionTest {
    @Test
    fun `root bounds should refute an exact contradiction beyond binary64 range`() {
        val huge = "1" + "0".repeat(399) + ".0"
        val source = SmtLib.parse("""
            (declare-const x Real)
            (assert (>= x $huge))
            (assert (<= x 1.0))
        """.trimIndent()).source
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.MIXED,
        ))

        val result = assertIs<SourceExecutionResult.Linear>(SourceSetup.execute(SourceExecutionRequest(invocation)))

        assertIs<OpenTheoryResult.Unsat>(assertIs<OpenTheoryExecution.Satisfy>(result.result).result)
    }

    @Test(timeout = 300)
    fun `mixed search should refute an unprojectable disjunct without a solver time limit`() {
        val tiny = "(/ 1.0 1" + "0".repeat(400) + ".0)"
        val source = SmtLib.parse("""
            (declare-const x Real)
            (declare-const n Int)
            (declare-const b Bool)
            (assert (<= 0.0 x))
            (assert (<= x 1.0))
            (assert (or b (>= (* $tiny x) 0.1)))
            (assert (=> b (> n 5)))
            (assert (=> b (< n 3)))
        """.trimIndent()).source
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.MIXED,
        ))

        val result = assertIs<SourceExecutionResult.Linear>(SourceSetup.execute(SourceExecutionRequest(invocation)))

        assertIs<OpenTheoryResult.Unsat>(assertIs<OpenTheoryExecution.Satisfy>(result.result).result)
    }
}
