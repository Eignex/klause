package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class AbsEncodingTest {
    @Test
    fun `absolute value installs a complete linear relation beyond its relaxation`() {
        val source = SmtLib.parse("(declare-const x Int) (assert (= (abs x) 2)) (assert (< x (- 2)))").model
        val relation = source.factors.filterIsInstance<Abs>().single()

        val result = BacktrackSolver.source(source).solve()

        assertIs<LinearForm.Relaxation>(relation.linearForm)
        assertEquals(EncoderSupport.EQUIVALENT, BuiltInEncoderProvider.select(relation, EncodingTarget.LINEAR).support)
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `sign conditions retract across exact absolute value queries`() {
        val input = SmtLib.parse("(declare-const b Bool) (declare-const x Int) " +
            "(assert (= (abs x) 2)) (assert (= b (>= x 0)))")
        val source = input.model
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())

        queries.use {
            for ((positive, expected) in listOf(true to "2", false to "-2", true to "2")) {
                val outcome = it.check(ExactNeighborhoodCondition(mapOf(0 to positive), emptyMap(), emptyMap()),
                    Cancellation.Never)
                val sample = assertIs<ExactNeighborhoodResult.Witness>(outcome).sample

                assertEquals(expected, sample.exactIntValue(input.intVarNames.getValue("x")).toString())
            }
        }
        assertNotNull(source.factors.filterIsInstance<Abs>().singleOrNull())
    }
}
