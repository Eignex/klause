package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneralWatchReclamationTest {
    @Test
    fun `small grants should release retired postings without changing learned handles`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = false)
        repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        val state = session.explanationState
        val capacity = state.watches.byLit[0].backingData.size
        val generation = session.learnedRetirementGeneration

        repeat(128) {
            val before = session.work
            session.compactLearnedClauses(256L, 256L, { false }) { _, _ -> true }
            assertTrue(session.work - before <= 256L)
        }

        assertEquals(0, state.watches.byLit[0].size)
        assertEquals(0, state.watches.byLit[2].size)
        assertTrue(state.watches.byLit[0].backingData.size < capacity)
        assertEquals(generation, session.learnedRetirementGeneration)
    }

    @Test
    fun `cancelled cleanup should resume from the same watcher generation`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = false)
        repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        val state = session.explanationState
        val before = state.watches.byLit[0].size
        val stopAt = session.work + 4L

        session.compactLearnedClauses(256L, 256L, { session.work >= stopAt }) { _, _ -> true }

        assertEquals(before, state.watches.byLit[0].size)
        repeat(128) { session.compactLearnedClauses(256L, 256L, { false }) { _, _ -> true } }
        assertEquals(0, state.watches.byLit[0].size)
    }

    @Test
    fun `a moved live watch should not hide retired entries in the scanned prefix`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(0, 2, 4)), 2, permanent = true)
        repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        session.compactLearnedClauses(32L, 256L, { false }) { _, _ -> true }

        session.pinBool(0, false)
        repeat(128) { session.compactLearnedClauses(256L, 256L, { false }) { _, _ -> true } }

        assertEquals(0, session.explanationState.watches.byLit[0].size)
        session.pinBool(1, false)
        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `bound atom postings should release retired factors under small grants`() {
        val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray()), nativeSat = false)
        val ge = session.boundGeLit(0, 5, positive = true)
        val le = session.boundLeLit(0, 7, positive = true)
        repeat(32) { session.addLearnedClause(Clause(intArrayOf(ge, le)), 9) }
        repeat(8) { session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false } }
        assertEquals(0, session.activeLearnedClauseCount)

        repeat(64) { session.compactLearnedClauses(256L, 256L, { false }) { _, _ -> true } }

        for (posting in session.explanationState.atoms.watchersByLit) assertEquals(0, posting?.size ?: 0)
    }

    @Test
    fun `permanent floors should permit bounded release of private watch capacity`() {
        for (permanentCount in listOf(4000, 20_000)) {
            val base = Clause(intArrayOf(8, 10))
            val session = PropagationSession(Problem(6, 0, emptyArray(), arrayOf(base)), nativeSat = false)
            repeat(permanentCount) { session.addLearnedClause(base, 2, permanent = true) }
            repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
            repeat(8) { session.maintainCapWindow(0, 32_768L, 4096L, { false }) { false } }
            val generation = session.learnedRetirementGeneration
            val before = session.explanationState.watches.byLit[0].backingData.size
            val indexBytes = session.explanationState.watches.pos.primitiveCapacityBytes
            val indexReferences = session.explanationState.watches.pos.referenceCapacity

            repeat(24) {
                val work = session.work
                session.compactLearnedClauses(32_768L, 4096L, { false }) { _, _ -> true }
                assertTrue(session.work - work <= 32_768L)
            }

            assertEquals(permanentCount, session.activeLearnedClauseCount)
            assertEquals(0, session.explanationState.watches.byLit[0].size)
            assertTrue(session.explanationState.watches.byLit[0].backingData.size < before)
            assertTrue(session.explanationState.watches.pos.size > 0)
            assertTrue(session.explanationState.watches.pos.primitiveCapacityBytes < indexBytes)
            assertTrue(session.explanationState.watches.pos.referenceCapacity < indexReferences)
            assertEquals(generation, session.learnedRetirementGeneration)
            session.pinBool(4, false)
            assertEquals(true, session.boolValue(5))
        }
    }
    @Test
    fun `a missing live back pointer should leave full compaction available`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        session.addLearnedClause(Clause(intArrayOf(0, 2, 4)), 2, permanent = true)
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        val state = session.explanationState
        state.watches.pos.remove(state.packWatch(session.problem.numFactors + 1, 0))

        session.forgetLearnedClauses { _, _ -> true }

        assertEquals(1, session.learnedClauseCount)
        session.pinBool(0, false)
        session.pinBool(1, false)
        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `large surviving postings should trim independently within default grants`() {
        val base = Clause(intArrayOf(0, 2))
        val session = PropagationSession(Problem(4, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(4, 6)))), nativeSat = false)
        repeat(20_000) { session.addLearnedClause(base, 2, permanent = true) }
        repeat(1024) { session.addLearnedClause(base, 9) }
        repeat(8) { session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false } }
        val watches = session.explanationState.watches
        val before = watches.byLit[0].backingData.size

        repeat(32) {
            val work = session.work
            session.compactLearnedClauses(65_536L, 1_048_576L, { false }) { _, _ -> true }
            assertTrue(session.work - work <= 65_536L)
        }

        assertEquals(20_000, session.activeLearnedClauseCount)
        assertEquals(20_000, watches.byLit[0].size)
        assertTrue(watches.byLit[0].backingData.size < before)
        assertEquals(20_000, watches.byLit[0].backingData.size)
        assertEquals(20_000, watches.blockersByLit[0].backingData.size)
        session.pinBool(0, false)
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `an empty watch position index should release its retained capacity`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), emptyArray()), nativeSat = false)
        val baseline = BoolWatchPositionIndex().primitiveCapacityBytes
        assertTrue(session.explanationState.watches.pos.primitiveCapacityBytes > baseline)
        repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        val generation = session.learnedRetirementGeneration

        repeat(128) {
            val work = session.work
            session.compactLearnedClauses(256L, 256L, { false }) { _, _ -> true }
            assertTrue(session.work - work <= 256L)
        }

        assertEquals(0, session.explanationState.watches.pos.size)
        assertEquals(baseline, session.explanationState.watches.pos.primitiveCapacityBytes)
        assertEquals(generation, session.learnedRetirementGeneration)
    }

    @Test
    fun `a larger grant should retry posting trims deferred by a completed scan`() {
        val clause = Clause(intArrayOf(0, 2))
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = false)
        repeat(1000) { session.addLearnedClause(clause, 2, permanent = true) }
        repeat(64) { session.addLearnedClause(clause, 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        repeat(160) { session.compactLearnedClauses(256L, 256L, { false }) { _, _ -> true } }
        val state = session.explanationState
        val before = state.watches.byLit[0].backingData.size
        val generation = session.learnedRetirementGeneration

        repeat(16) {
            val work = session.work
            session.compactLearnedClauses(8192L, 4096L, { false }) { _, _ -> true }
            assertTrue(session.work - work <= 8192L)
        }

        assertTrue(state.watches.byLit[0].backingData.size < before)
        assertEquals(1000, state.watches.byLit[0].backingData.size)
        assertEquals(1000, state.watches.blockersByLit[0].backingData.size)
        assertEquals(generation, session.learnedRetirementGeneration)
    }

}
