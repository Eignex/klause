package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArithmeticLearningTraceTest {
    @Test
    fun `capture should own payloads and stop at its word allowance`() {
        val trace = ArithmeticLearningTrace(maxEvents = 4, maxWords = 3)
        val payload = longArrayOf(1, 2)
        trace.record("seed", *payload)
        payload[0] = 9

        trace.record("overflow", 3, 4)
        repeat(10) { trace.record("later", 5) }

        assertContentEquals(longArrayOf(1, 2), trace.events.single().data)
        assertEquals(2, trace.words)
        assertTrue(trace.truncated)
    }

    @Test
    fun `empty events should consume the event allowance`() {
        val trace = ArithmeticLearningTrace(maxEvents = 2, maxWords = 3)
        repeat(10) { trace.record("decline") }

        assertEquals(2, trace.events.size)
        assertTrue(trace.truncated)
    }

    @Test
    fun `consumer identity should survive compaction and retire on forgetting`() {
        val trace = ArithmeticLearningTrace()
        trace.stored(4, "integer")
        trace.remap(3, intArrayOf(-1, 0))
        trace.pin(3, 0, 2, "upper", 1)
        trace.remap(3, intArrayOf(-1))
        trace.pin(3, 0, 3, "upper", 1)

        val consumer = trace.events.single { it.kind == "consumer.upper" }
        assertContentEquals(longArrayOf(0, 3, 0, 2, 1), consumer.data)
    }

    @Test
    fun `bounded capture should preserve conflict fallback and later row deduction`() {
        val work = mutableListOf<Long>()
        for (capture in listOf(false, true)) {
            val native = PropagationSession(Problem(0, 3,
                arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                    Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                    Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                )), pbLearning = false).apply { enableIntegerRowLearning() }
            val trace = ArithmeticLearningTrace()
            if (capture) native.explanationState.arithmeticLearningTrace = trace
            val cp = CpSearchComponent(native)
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(shared.initialize())
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(2, 7)))
            assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(0, 2)))
            val learned = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
            shared.popTo(learned.decisionLevel)
            assertIs<SearchLearnedConflictResult.Resume>(learned.apply(shared))
            shared.popTo(0)

            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(2, 11)))

            assertEquals(2L, native.intDomain(0).max)
            work.add(native.explanationState.work)
            if (capture) {
                assertTrue(trace.events.any { it.kind == "integer.pivot-row" })
                assertTrue(trace.events.any { it.kind == "integer.fallback" })
                assertTrue(trace.events.any { it.kind == "analysis.mapped-target" })
                assertTrue(trace.events.any { it.kind == "consumer.upper" && it.data[3] == 2L })
                assertFalse(trace.truncated)
            }
        }
        assertEquals(work[0], work[1])
    }
}
