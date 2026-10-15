package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.simplex.exact.BigFraction
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
import kotlin.test.assertTrue

class RealAbsEncodingTest {
    @Test
    fun `exact FlatZinc absolute value retains the native relation at a rational point`() {
        val parsed = parseFlatZinc(
            """var -2.0..2.0: x; var 0.0..2.0: y;
                constraint float_abs(x,y); constraint float_lin_eq([3.0],[x],-1.0); solve satisfy;""",
            exactFloats = true,
        )
        val relation = parsed.problem.factors.filterIsInstance<RealAbs>().single()

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.problem).solve())

        assertEquals("-1/3", result.assignment.realValue(relation.operand))
        assertEquals("1/3", result.assignment.realValue(relation.result))
    }

    @Test
    fun `exact real absolute value rejects a point permitted by its relaxation`() {
        val parsed = parseFlatZinc(
            """var -2.0..2.0: x; var 0.0..2.0: y; constraint float_abs(x,y);
                constraint float_eq(x,0.0); constraint float_eq(y,1.0); solve satisfy;""",
            exactFloats = true,
        )
        assertIs<LinearForm.Relaxation>(parsed.problem.factors.filterIsInstance<RealAbs>().single().linearForm)

        val result = BacktrackSolver.source(parsed.problem).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `real absolute value aliases exclude negative source coordinates`() {
        val parsed = parseFlatZinc(
            """var -2.0..2.0: x; constraint float_abs(x,x);
                constraint float_lt(x,0.0); solve satisfy;""",
            exactFloats = true,
        )

        val result = BacktrackSolver.source(parsed.problem).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `real absolute value retracts a contradictory reified source condition`() {
        val parsed = parseFlatZinc(
            """var bool: zero; var -2.0..2.0: x; var 0.0..2.0: y;
                constraint float_abs(x,y); constraint float_eq(y,1.0);
                constraint float_eq_reif(x,0.0,zero); solve satisfy;""",
            exactFloats = true,
        )
        val source = parsed.problem
        val relation = source.factors.filterIsInstance<RealAbs>().single()
        val boolean = parsed.boolVarsByName.getValue("zero")
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())
        queries.use {
            val refuted = ExactNeighborhoodCondition(mapOf(boolean to true), emptyMap(), emptyMap())
            val feasible = ExactNeighborhoodCondition(mapOf(boolean to false), emptyMap(), emptyMap())
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(refuted, Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(feasible, Cancellation { true }))

            val sample = assertIs<ExactNeighborhoodResult.Witness>(it.check(feasible, Cancellation.Never)).sample

            val values = assertNotNull(sample.exactReals)
            assertEquals(BigFraction.ONE, values[relation.result])
            assertTrue(values[relation.operand] in listOf(BigFraction.ONE, BigFraction.MINUS_ONE))
            assertEquals(source.numRealVars, values.size)
        }
    }
}
