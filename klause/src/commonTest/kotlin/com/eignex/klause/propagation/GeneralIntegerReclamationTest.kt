package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GeneralIntegerReclamationTest {
    @Test
    fun `an empty integer cache should release event ownership beside a growing permanent floor`() {
        val session = PropagationSession(Problem(6, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))), nativeSat = false)
        val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 10), useLo = true))
        repeat(64) { session.addLearnedIntegerRow(row, 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        assertEquals(0, session.activeLearnedClauseCount)
        val floor = Clause(intArrayOf(8, 10))
        repeat(4000) { session.addLearnedClause(floor, 2, permanent = true) }
        val state = session.explanationState
        assertNotNull(state.intEvents.overflow)
        val generation = session.learnedRetirementGeneration

        val result = session.reclaimLearnedMemory(0L, maxWork = 16L, maxScratchBytes = 256L)

        assertEquals(16L, result.work)
        assertNull(result.beforeBytes)
        assertNull(state.intEvents.overflow)
        assertEquals(4000, session.activeLearnedClauseCount)
        assertEquals(generation, session.learnedRetirementGeneration)
        session.pinBool(4, false)
        assertEquals(true, session.boolValue(5))
        val next = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 8), useLo = true))
        session.addLearnedIntegerRow(next, 2)
        assertNotNull(state.intEvents.overflow)
        assertEquals(IntDomain(0, 8), session.intDomain(0))
    }

    @Test
    fun `cancellation after funding should preserve an empty integer event owner until retry`() {
        var limit = Long.MAX_VALUE
        lateinit var session: PropagationSession
        session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))), nativeSat = false,
            cancellation = Cancellation { limit != Long.MAX_VALUE && session.work >= limit })
        val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 10), useLo = true))
        session.addLearnedIntegerRow(row, 9)
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        assertEquals(0, session.activeLearnedClauseCount)
        val events = assertNotNull(session.explanationState.intEvents.overflow)
        limit = session.work + 16L

        val interrupted = session.reclaimLearnedMemory(0L, maxWork = 16L, maxScratchBytes = 256L)

        assertEquals(ReclamationStatus.CANCELLED, interrupted.status)
        assertEquals(events, session.explanationState.intEvents.overflow)
        limit = Long.MAX_VALUE
        session.reclaimLearnedMemory(0L, maxWork = 16L, maxScratchBytes = 256L)
        assertNull(session.explanationState.intEvents.overflow)
    }

    @Test
    fun `a surviving integer cache should release spare reference slots within its own grant`() {
        val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))), nativeSat = false)
        val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 10), useLo = true))
        repeat(64) { session.addLearnedIntegerRow(row, 9) }
        session.maintainCapWindow(1, 65_536L, 1_048_576L, { false }) { it == 0 }
        val rows = assertNotNull(session.explanationState.learned.integerRows)
        assertEquals(1, rows.size)
        assertEquals(64, rows.capacity)
        val generation = session.learnedRetirementGeneration
        val before = session.work

        session.compactLearnedClauses(3L, 8L, { false }) { _, _ -> true }

        assertEquals(3L, session.work - before)
        assertEquals(1, rows.capacity)
        assertEquals(1, rows.size)
        assertNotNull(session.explanationState.intEvents.overflow)
        assertEquals(generation, session.learnedRetirementGeneration)
    }
}
