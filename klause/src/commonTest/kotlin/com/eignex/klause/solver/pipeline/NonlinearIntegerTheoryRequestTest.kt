package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.times
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NonlinearIntegerTheoryRequestTest {
    @Test
    fun `every engine discovers a checked open integer square witness`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const n Int) (assert (= (* n n) 4))",
        )).problem

        val engines = listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED)
        for (engine in engines) {
            val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = engine)

            val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
            val value = parseBigInt(witness.intValue(0))
            assertEquals(parseBigInt("4"), value * value)
            assertTrue(execution.stats.workSpent > 0)
        }
    }

    @Test
    fun `heuristic neighborhoods preserve arbitrary width integer output`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const n Int) (assert (= n 100000000000000000000)) " +
                "(assert (= (* n n) 10000000000000000000000000000000000000000))",
        )).problem

        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = engine)

            val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
            assertEquals("100000000000000000000", witness.intValue(0))
            assertTrue(execution.result.stats.ls.moves.sum > 0)
        }
    }

    @Test
    fun `mixed output mapping separates interleaved real and integer declarations`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const x Real) (declare-const n Int) " +
                "(assert (= (* n n) 4)) (assert (> n 0)) (assert (= (* x x) (to_real n)))",
        )).problem

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        assertEquals("2", witness.intValue(0))
        assertNotNull(witness.algebraicRealValue(0))
    }

    @Test
    fun `finite mixed products complete through the shared factor model`() {
        val source = assertIs<SmtLibInput.Linear>(SmtLibAdmission.parse(
            "(declare-const n Int) (declare-const x Real) " +
                "(assert (and (<= 1 n 3) (<= 1.0 x 2.0) (= (* (to_real n) x) 6.0)))",
        )).problem
        val route = assertIs<SourceProblemRoute.PolynomialTheory>(source.model.pipelineRoute())

        val execution = OpenTheoryPipeline.execute(route.request, engine = FiniteEngine.BACKTRACK)

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        assertEquals("3", witness.intValue(source.intVarNames.getValue("n")))
        assertEquals("2", witness.realValue(source.realVarNames.getValue("x")))
    }

    @Test
    fun `failed exact integer neighborhoods retain unknown proof status`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const n Int) (assert (= (* n n) 2))",
        )).problem

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), NonlinearIntegerTheoryParams(
            maximumCandidates = 2, maximumMoves = 2,
        ), FiniteEngine.LOCAL_SEARCH)

        assertIs<OpenTheoryResult.Unknown>(execution.result)
        assertTrue(execution.result.stats.ls.moves.sum > 0)
    }

    @Test
    fun `Boolean invocation assumptions remain local to the source call`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const n Int) (declare-const p Bool) (assert (= (* n n) 4)) (assert p)",
        )).problem
        val rejected = NonlinearIntegerTheoryParams(polynomial = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(assumptions = Assumptions.None.withBool(0, false)),
        ))

        val conditioned = OpenTheoryPipeline.execute(source.pipelineRequest(), rejected, FiniteEngine.BACKTRACK)
        val fresh = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Unsat>(conditioned.result)
        assertTrue(assertIs<OpenTheoryResult.Sat>(fresh.result).assignment.boolValue(0))
    }

    @Test
    fun `native work charges remain cumulative across heuristic callbacks`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const n Int) (assert (= (* n n) 2))",
        )).problem
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), NonlinearIntegerTheoryParams(
            polynomial = PolynomialTheoryParams(maximumWork = 128, cancellation = cancellation),
            maximumMoves = 8,
        ), FiniteEngine.LOCAL_SEARCH)

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(charged, execution.stats.workSpent)
        assertTrue(charged >= 128)
    }

    @Test
    fun `an exhausted solve wide node allowance prevents nonlinear entry`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const n Int) (assert (= (* n n) 4))",
        )).problem

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), NonlinearIntegerTheoryParams(
            nodeBudget = NodeBudget(0),
        ), FiniteEngine.MIXED)

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(0L, execution.stats.workSpent)
    }
}
