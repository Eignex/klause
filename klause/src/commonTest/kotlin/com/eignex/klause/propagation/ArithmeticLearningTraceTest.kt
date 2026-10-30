package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArithmeticLearningTraceTest {
    @Test
    fun `focused branch markers should retain one attempt per consumer within the cap`() {
        for (kind in listOf("traversal.pick", "traversal.attempt")) {
            val trace = ArithmeticLearningTrace(maxEvents = 4, maxWords = 16).apply { captureFirstInteger = true }
            trace.stored(4, "integer")
            trace.record(kind, 1)
            repeat(8) { trace.record(kind, 2) }

            trace.pin(4, 0, 2, "upper", 2)
            trace.record(kind, 3)

            assertEquals(4, trace.events.size)
            assertContentEquals(longArrayOf(1, 3), trace.events.filter { it.kind == kind }
                .map { it.data[0] }.toLongArray())
            assertEquals(8L, trace.omittedObservations)
            assertFalse(trace.truncated)
        }
    }

    @Test
    fun `row capture should retain signed Boolean and order guards`() {
        val state = PropagationState(Problem(1, 1, arrayOf(IntDomain(0, 6)), emptyArray()), Assumptions.None)
        val selected = Lit.make(state.atomVarEq(0, 2), true)
        for (guard in intArrayOf(Lit.make(0, false), selected)) {
            val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
                longArrayOf(1), intArrayOf(0), LinearOp.LE, 6), useLo = true, guard = guard))
            val trace = ArithmeticLearningTrace()

            trace.row("candidate", row, state)

            assertContentEquals(longArrayOf(guard.toLong()), trace.events.single { it.kind == "candidate.guard" }.data)
            val literal = trace.events.single { it.kind == "candidate.guard-literal" }.data
            assertEquals(guard.toLong(), literal[0])
            if (guard == selected) {
                assertEquals(0L, literal[2])
                assertEquals(AtomKind.EQ.ordinal.toLong(), literal[3])
                assertEquals(2L, literal[4])
            }
            assertFalse(trace.truncated)
        }
    }

    @Test
    fun `capture should attribute PB rejection of a nonboolean reason`() {
        val state = PropagationState(Problem(1, 1, arrayOf(IntDomain(0, 6)), emptyArray()), Assumptions.None)
        val integer = state.atomVarGe(0, 1)
        val unavailable = state.problem.numBoolVars + state.atoms.intVar.size + 1
        for (atom in intArrayOf(integer, unavailable)) {
            val trace = ArithmeticLearningTrace()
            state.arithmeticLearningTrace = trace
            val resolvent = PbConflictResolvent(state, state.conflictAnalyzer)

            resolvent.resolve(intArrayOf(Lit.make(atom, false)), 1)

            assertTrue(resolvent.failed)
            assertEquals("pb.decline.non-boolean-reason", trace.events.last().kind)
            assertFalse(trace.truncated)
        }
    }

    @Test
    fun `historical capture should exclude a later journalled hole`() {
        val state = PropagationState(Problem(0, 1,
            arrayOf(IntDomain(0, 6).excludeValue(1)), emptyArray()), Assumptions.None)
        state.undoLogging = true
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 6), useLo = true))
        assertTrue(state.excludeIntValueImpl(0, 3, null))
        val trace = ArithmeticLearningTrace()

        trace.row("historical", row, state, trail = 0)

        assertContentEquals(longArrayOf(0, 1), trace.events.single { it.kind == "historical.hole" }.data)
        assertFalse(trace.truncated)
    }

    @Test
    fun `oversized reason payloads should preserve later consumers`() {
        val state = PropagationState(Problem(0, 0, emptyArray(), emptyArray()), Assumptions.None)
        for (marker in listOf(false, true)) {
            val trace = ArithmeticLearningTrace()
            trace.stored(4, "integer")
            if (marker) trace.marker(0, true, IntArray(33))
            else trace.clause("analysis.seed-clause", IntArray(65), state)

            trace.pin(4, 0, 2, "upper", 1)

            assertContentEquals(longArrayOf(if (marker) 33L else 65L),
                trace.events.single { it.kind.endsWith(".omitted") }.data)
            assertTrue(trace.events.any { it.kind == "consumer.upper" })
            assertEquals(1, trace.omittedPayloads)
            assertFalse(trace.truncated)
        }
    }

    @Test
    fun `focused capture should retain a derivation and later consumers within its allowance`() {
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 6)), emptyArray()), Assumptions.None)
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 6), useLo = true))
        val trace = ArithmeticLearningTrace().apply { captureFirstInteger = true }
        trace.record("prefix", 1)
        trace.beginConflict(0, 1, 0)
        trace.row("integer.derived", row, state)
        trace.stored(4, "integer")
        trace.pin(4, 0, 1, "upper", 1)
        trace.record("traversal.pick", 2)
        repeat(2_000) {
            trace.beginConflict(0, 2, 0)
            trace.record("traversal.pick", 2)
        }

        trace.pin(4, 0, 2, "upper", 2)
        trace.remap(3, intArrayOf(-1, -1))

        assertTrue(trace.events.any { it.kind == "integer.derived.endpoint" })
        assertContentEquals(longArrayOf(1, 2), trace.events.filter { it.kind == "consumer.upper" }
            .map { it.data[3] }.toLongArray())
        assertEquals(1, trace.events.count { it.kind == "traversal.pick" })
        assertEquals(-1L, trace.events.last().data[2])
        assertEquals(1L, trace.discardedWindows)
        assertTrue(trace.omittedObservations >= 2_000L)
        assertFalse(trace.truncated)
    }

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
        assertEquals("words", trace.truncationCause)
    }

    @Test
    fun `empty events should consume the event allowance`() {
        val trace = ArithmeticLearningTrace(maxEvents = 2, maxWords = 3)
        val state = PropagationState(Problem(0, 0, emptyArray(), emptyArray()), Assumptions.None)
        state.arithmeticLearningTrace = trace
        repeat(10) { state.arithmeticLearningTrace?.record("decline") }

        assertEquals(2, trace.events.size)
        assertTrue(trace.truncated)
        assertEquals("events", trace.truncationCause)
        assertNull(state.arithmeticLearningTrace)
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
        for (capture in 0..2) {
            val native = PropagationSession(Problem(0, 3,
                arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                    Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                    Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                )), pbLearning = false).apply { enableIntegerRowLearning() }
            val trace = ArithmeticLearningTrace().apply { captureFirstInteger = capture == 2 }
            if (capture != 0) native.explanationState.arithmeticLearningTrace = trace
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
            assertTrue(native.learnedLifecycle().getValue("learningIntegerAdmitted") > 0L)
            assertTrue(native.learnedLifecycle().getValue("learningIntegerConsumed") > 0L)
            work.add(native.explanationState.work)
            if (capture != 0) {
                assertTrue(trace.events.any { it.kind == "analysis.seed-endpoint" })
                assertTrue(trace.events.any { it.kind == "integer.pivot-row" })
                assertTrue(trace.events.any { it.kind == "integer.fallback" })
                assertTrue(trace.events.any { it.kind == "analysis.mapped-target" })
                assertTrue(trace.events.any { it.kind == "consumer.upper" && it.data[3] == 2L })
                assertFalse(trace.truncated)
            }
        }
        assertEquals(1, work.distinct().size)
    }
}
