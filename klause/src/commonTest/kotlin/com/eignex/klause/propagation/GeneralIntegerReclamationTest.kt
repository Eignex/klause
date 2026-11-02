package com.eignex.klause.propagation

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.LearnedMemoryMaintenance
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
import kotlin.test.assertTrue

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

    @Test
    fun `nonempty integer event cleanup should release retired entries beside a permanent floor`() {
        val session = PropagationSession(Problem(6, 66, Array(66) { IntDomain(0, 10) },
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))), nativeSat = false)
        val live = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 8), useLo = true))
        session.addLearnedIntegerRow(live, 2)
        val state = session.explanationState
        val events = assertNotNull(state.intEvents.overflow)
        val bytes = events.primitiveCapacityBytes
        val references = events.referenceCapacity
        val reason = state.intMaxReason[0]
        repeat(64) { variable ->
            val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(variable + 2),
                longArrayOf(1), intArrayOf(variable + 2), LinearOp.LE, 10), useLo = true))
            session.addLearnedIntegerRow(row, 9)
        }
        repeat(8) { session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false } }
        val floor = Clause(intArrayOf(8, 10))
        repeat(4000) { session.addLearnedClause(floor, 2, permanent = true) }
        val generation = session.learnedRetirementGeneration

        repeat(256) {
            val work = session.work
            session.compactLearnedClauses(256L, 256L, { false }) { _, _ -> true }
            assertTrue(session.work - work <= 256L)
        }

        assertEquals(4, events.size)
        assertEquals(bytes, events.primitiveCapacityBytes)
        assertEquals(references, events.referenceCapacity)
        assertEquals(reason, state.intMaxReason[0])
        assertEquals(generation, session.learnedRetirementGeneration)
        session.pinInt(1, 7L)
        assertEquals(IntDomain(0, 1), session.intDomain(0))
    }

    @Test
    fun `growing integer retirements should release event ownership at an unchanged root`() {
        val session = PropagationSession(Problem(6, 66, Array(66) { IntDomain(0, 10) },
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))), nativeSat = false)
        val live = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
            longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 8), useLo = true))
        session.addLearnedIntegerRow(live, 2)
        val state = session.explanationState
        val events = assertNotNull(state.intEvents.overflow)
        val bytes = events.primitiveCapacityBytes
        val references = events.referenceCapacity
        val floor = Clause(intArrayOf(8, 10))
        repeat(20_000) { session.addLearnedClause(floor, 2, permanent = true) }
        val generation = session.learnedRetirementGeneration
        val maintenance = LearnedMemoryMaintenance()
        val params = BacktrackParams(learnedMemoryBytes = 0L,
            learnedMemoryWork = 32_768L, learnedMemoryScratchBytes = 4096L)

        repeat(3) {
            repeat(64) { variable ->
                val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(),
                    intArrayOf(variable + 2), longArrayOf(1), intArrayOf(variable + 2), LinearOp.LE, 10),
                    useLo = true))
                session.addLearnedIntegerRow(row, 9)
            }
            repeat(8) { session.maintainCapWindow(0, 32_768L, 4096L, { false }) { false } }
            repeat(64) {
                val work = session.work
                maintenance.run(session, params, null, null)
                assertTrue(session.work - work <= 32_768L)
            }
            assertEquals(4, events.size)
            assertEquals(bytes, events.primitiveCapacityBytes)
            assertEquals(references, events.referenceCapacity)
            assertEquals(20_001, session.activeLearnedClauseCount)
            assertEquals(generation, session.learnedRetirementGeneration)
        }
        session.pinInt(1, 7L)
        assertEquals(IntDomain(0, 1), session.intDomain(0))
        session.releaseRetainedState()
        assertEquals(com.eignex.klause.util.SharingMemoryStatus.STALE, session.sharingMemory().status)
    }

    @Test
    fun `small scratch retirement should preserve current and historical integer reasons`() {
        val session = PropagationSession(Problem(6, 2, Array(2) { IntDomain(0, 10) },
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))), nativeSat = false)
        val first = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 8), useLo = true))
        val second = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 6), useLo = true))
        val unused = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(1),
            longArrayOf(1), intArrayOf(1), LinearOp.LE, 10), useLo = true))
        session.addLearnedIntegerRow(first, 9)
        session.addLearnedIntegerRow(second, 9)
        session.addLearnedIntegerRow(unused, 9)
        val floor = Clause(intArrayOf(8, 10))
        repeat(64) { session.addLearnedClause(floor, 2, permanent = true) }
        val state = session.explanationState
        val reason = state.intMaxReason[0]

        repeat(64) {
            val work = session.work
            session.maintainCapWindow(0, 256L, 64L, { false }) { false }
            assertTrue(session.work - work <= 256L)
        }

        assertEquals(66, session.activeLearnedClauseCount)
        assertEquals(2, assertNotNull(state.learned.integerRows).size)
        assertEquals(reason, state.intMaxReason[0])
        assertEquals(IntDomain(0, 6), session.intDomain(0))
    }


    @Test
    fun `cancellation after funding should preserve paged integer cache capacity`() {
        val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))), nativeSat = false)
        val live = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 8), useLo = true))
        val unused = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 10), useLo = true))
        session.addLearnedIntegerRow(live, 2)
        repeat(255) { session.addLearnedIntegerRow(unused, 9) }
        session.maintainCapWindow(1, 65_536L, 1_048_576L, { false }) { it == 0 }
        val rows = assertNotNull(session.explanationState.learned.integerRows)
        assertEquals(1, rows.size)
        assertEquals(256, rows.capacity)
        val stopAt = session.work + 7L

        session.compactLearnedClauses(7L, 8L, { session.work >= stopAt }) { _, _ -> true }

        assertEquals(stopAt, session.work)
        assertEquals(256, rows.capacity)
        session.compactLearnedClauses(7L, 8L, { false }) { _, _ -> true }
        assertEquals(1, rows.capacity)
        assertEquals(IntDomain(0, 8), session.intDomain(0))
    }

}
