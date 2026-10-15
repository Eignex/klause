package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscendentalWorkerAdapterTest {
    @Test
    fun `unresolved numeric root should keep its charges across slices`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val source = TranscendentalProblem(
            TranscendentalFormula.Atom(
                TranscendentalExpression.Function(TranscendentalFunction.EXP, x),
                PolynomialRelation.EQ,
                x,
            ),
            emptyMap(),
            mapOf("x" to 0),
            emptyMap(),
        )
        val params = TranscendentalTheoryParams(maximumDepth = 1)
        val adapter = TranscendentalWorkerAdapter(source, params, FiniteEngine.BACKTRACK)

        val sliced = adapter.completeHandle().use { handle ->
            assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1))
            assertTrue(adapter.nativeWork > 0)
            assertIs<SolveResult.Unknown>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1))
        }
        val unsliced = TranscendentalWorkerAdapter(source, params, FiniteEngine.BACKTRACK).use { it.execute() }

        assertIs<OpenTheoryResult.Unknown>(unsliced.result)
        assertEquals(unsliced.workSpent, adapter.nativeWork)
        assertEquals(unsliced.result.stats.search.nodes, sliced.stats.search.nodes)
        adapter.close()
    }

    @Test
    fun `closing an unused source worker should prevent opening a traversal`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), emptyMap(), emptyMap(), emptyMap())
        val adapter = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK)
        adapter.close()

        assertFailsWith<IllegalStateException> { adapter.completeHandle() }
        assertEquals(0L, adapter.nativeWork)
    }

    @Test
    fun `sliced refuted Boolean branch should keep its feasible sibling`() {
        val source = TranscendentalProblem(
            TranscendentalFormula.BooleanVariable(SourceBoolId(0)),
            emptyMap(),
            emptyMap(),
            mapOf("b" to 0),
        )
        val adapter = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK)

        val sliced = adapter.completeHandle().use { handle ->
            assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1))
            val spent = adapter.nativeWork
            assertTrue(spent > 0)
            val result = assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1))
            assertTrue(adapter.nativeWork > spent)
            result
        }
        val unsliced = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK)
        val result = unsliced.execute()

        assertEquals(true, sliced.assignment.boolValue(0))
        assertIs<OpenTheoryResult.Sat>(result.result)
        assertEquals(result.workSpent, adapter.nativeWork)
        assertEquals(result.result.stats.search.nodes, sliced.stats.search.nodes)
    }

    @Test
    fun `zero slice should spend no arithmetic before a later slice`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), emptyMap(), emptyMap(), emptyMap())
        val adapter = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK)

        adapter.completeHandle().use { handle ->
            assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 0))
            assertEquals(0L, adapter.nativeWork)
            assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1))
        }
    }

    @Test
    fun `lifetime cancellation should finish rather than pause an active handle`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), emptyMap(), emptyMap(), emptyMap())
        val adapter = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK)

        adapter.completeHandle().use { handle ->
            val result = assertIs<SolveResult.Unknown>(handle.runSlice(Cancellation { true }, Long.MAX_VALUE, 1))
            assertEquals(TerminationReason.Cancelled, result.reason)
            assertIs<SolveResult.Unknown>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1))
            assertTrue(handle.isDone)
        }
        assertEquals(0L, adapter.nativeWork)
    }

    @Test
    fun `closing a pending handle should reject resumption without native work`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), emptyMap(), emptyMap(), emptyMap())
        val adapter = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK)
        val handle = adapter.completeHandle()

        handle.close()
        handle.close()

        assertFailsWith<IllegalStateException> { handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) }
        assertEquals(0L, adapter.nativeWork)
    }

    @Test
    fun `published point should survive close but not cross invocation ownership`() {
        val source = TranscendentalProblem(
            TranscendentalFormula.BooleanVariable(SourceBoolId(0)),
            emptyMap(),
            emptyMap(),
            mapOf("b" to 0),
        )
        val identity = ModelIdentity.of(source)
        val binding = EngineInvocation(source.prepareTranscendentalExecutionPlan(), identity)
        val adapter = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK, binding)
        val sample = adapter.completeHandle().use { handle ->
            assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1)).assignment
        }
        adapter.close()
        val foreign = EngineInvocation(source.prepareTranscendentalExecutionPlan(), identity)

        val accepted = polynomialWitnessVerifier(binding, Cancellation.Never).verify(Candidate(sample, null))
        val rejected = polynomialWitnessVerifier(foreign, Cancellation.Never).verify(Candidate(sample, null))

        assertIs<Verification.Accepted<*, *>>(accepted)
        assertIs<Verification.Rejected>(rejected)
        assertEquals(true, sample.boolValue(0))
    }

    @Test
    fun `separate workers should keep separate conditions and arithmetic costs`() {
        val source = TranscendentalProblem(
            TranscendentalFormula.BooleanVariable(SourceBoolId(0)),
            emptyMap(),
            emptyMap(),
            mapOf("b" to 0),
        )
        val params = TranscendentalTheoryParams()
        val first = TranscendentalWorkerAdapter(source, params, FiniteEngine.BACKTRACK)
        val second = TranscendentalWorkerAdapter(
            source,
            params.copy(
                strategy = params.strategy.copy(
                    assumptions = Assumptions.None.withBool(0, false),
                ),
            ),
            FiniteEngine.BACKTRACK,
        )

        val feasible = first.execute()
        val spent = first.nativeWork
        val refuted = second.execute()

        assertIs<OpenTheoryResult.Sat>(feasible.result)
        assertIs<OpenTheoryResult.Unsat>(refuted.result)
        assertEquals(spent, first.nativeWork)
        assertTrue(second.nativeWork > 0)
    }
}
