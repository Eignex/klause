package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibPowersTest {
    @Test
    fun `lexical and bounded powers agree with original signed roots`() {
        val bounded = SmtLib.parse("(declare-const x Int) " +
            "(assert (and (<= (- 3) x 3) (= (^ x 4) 16)))")
        val lexical = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const x Int) (define-fun fourth ((y Int)) Int (^ y 4)) " +
                "(assert (and (<= (- 3) x 3) (= (fourth x) 16)))",
        )).problem
        assertEquals(4, bounded.model.factors.filterIsInstance<Power>().single().exponent)
        val finite = BacktrackSolver(bounded.model.bake()).enumerate(BacktrackParams(maxDecisions = 50))
            .map { it.intValue(0).toString() }.toSet()

        val result = OpenTheoryPipeline.execute(lexical.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        val witness = assertIs<OpenTheoryResult.Sat>(result.result).assignment.intValue(0)
        assertEquals(setOf("-2", "2"), finite)
        assertTrue(witness in finite)
    }

    @Test
    fun `wide fixed integer powers preserve arbitrary precision source values`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const x Int) (assert (= x 100000000000000000000)) " +
                "(assert (= (^ x 2) 10000000000000000000000000000000000000000))",
        )).problem

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        assertEquals("100000000000000000000", assertIs<OpenTheoryResult.Sat>(execution.result).assignment.intValue(0))
    }

    @Test
    fun `constant and identity powers retain their linear admission`() {
        for (expression in listOf("(^ x 0)", "(^ 2 3)", "(^ (^ 2 3) 1)")) {
            val source = "(declare-const x Int) (assert (= $expression $expression))"

            assertIs<SmtLibInput.Linear>(SmtLibAdmission.parse(source))
        }
    }
}
