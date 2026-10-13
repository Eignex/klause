package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.TranscendentalFormula
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
    fun `a sliced refuted Boolean branch retains its feasible sibling`() {
        val source = TranscendentalProblem(TranscendentalFormula.BooleanVariable(SourceBoolId(0)),
            emptyMap(), emptyMap(), mapOf("b" to 0))
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
    fun `a zero slice spends no arithmetic before a later slice`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), emptyMap(), emptyMap(), emptyMap())
        val adapter = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK)

        adapter.completeHandle().use { handle ->
            assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 0))
            assertEquals(0L, adapter.nativeWork)
            assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1))
        }
    }

    @Test
    fun `lifetime cancellation finishes rather than pausing an active handle`() {
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
    fun `closing a pending handle rejects resumption without native work`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), emptyMap(), emptyMap(), emptyMap())
        val adapter = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK)
        val handle = adapter.completeHandle()

        handle.close()
        handle.close()

        assertFailsWith<IllegalStateException> { handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) }
        assertEquals(0L, adapter.nativeWork)
    }

    @Test
    fun `closing a source worker retains charges and rejects another source query`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), emptyMap(), emptyMap(), emptyMap())
        val adapter = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK)
        val result = adapter.execute()
        val work = adapter.nativeWork

        adapter.close()
        adapter.close()

        assertIs<OpenTheoryResult.Sat>(result.result)
        assertEquals(work, adapter.nativeWork)
        assertFailsWith<IllegalStateException> { adapter.execute() }
    }

    @Test
    fun `a published point survives close but cannot cross invocation ownership`() {
        val source = TranscendentalProblem(TranscendentalFormula.BooleanVariable(SourceBoolId(0)),
            emptyMap(), emptyMap(), mapOf("b" to 0))
        val identity = ModelIdentity.of(source)
        val binding = EngineInvocation(source.prepareTranscendentalExecutionPlan(), identity)
        val adapter = TranscendentalWorkerAdapter(source, TranscendentalTheoryParams(), FiniteEngine.BACKTRACK, binding)
        val sample = adapter.completeHandle().use { handle ->
            assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1)).assignment
        }
        val foreign = EngineInvocation(source.prepareTranscendentalExecutionPlan(), identity)

        val accepted = polynomialWitnessVerifier(binding, Cancellation.Never).verify(Candidate(sample, null))
        val rejected = polynomialWitnessVerifier(foreign, Cancellation.Never).verify(Candidate(sample, null))

        assertIs<Verification.Accepted<*, *>>(accepted)
        assertIs<Verification.Rejected>(rejected)
        assertEquals(true, sample.boolValue(0))
    }

    @Test
    fun `separate workers retain separate conditions and arithmetic costs`() {
        val source = TranscendentalProblem(TranscendentalFormula.BooleanVariable(SourceBoolId(0)),
            emptyMap(), emptyMap(), mapOf("b" to 0))
        val params = TranscendentalTheoryParams()
        val first = TranscendentalWorkerAdapter(source, params, FiniteEngine.BACKTRACK)
        val second = TranscendentalWorkerAdapter(source, params.copy(strategy = params.strategy.copy(
            assumptions = Assumptions.None.withBool(0, false))), FiniteEngine.BACKTRACK)

        val feasible = first.execute()
        val spent = first.nativeWork
        val refuted = second.execute()

        assertIs<OpenTheoryResult.Sat>(feasible.result)
        assertIs<OpenTheoryResult.Unsat>(refuted.result)
        assertEquals(spent, first.nativeWork)
        assertTrue(second.nativeWork > 0)
    }
}
