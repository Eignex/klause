package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.SharingMemoryStatus
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class GeneralWatchMemoryContinuationTest(private val permanentCount: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "permanentCount={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf<Any>(4_000), arrayOf<Any>(20_000))
    }

    @Test
    fun `partial observations should permit bounded watch cleanup across unchanged roots`() {
        val floor = Clause(intArrayOf(8, 10))
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(floor, Clause(intArrayOf(1)))), nativeSat = false)
        repeat(permanentCount) { session.addLearnedClause(floor, 2, permanent = true) }
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        repeat(64) { session.addLearnedClause(Clause(intArrayOf(4, 6)), 9) }
        repeat(8) { session.maintainCapWindow(0, 32_768L, 4096L, { false }) { false } }
        val state = session.explanationState
        val posting = state.watches.byLit[4].backingData.size
        val reason = assertNotNull(session.boolReasonClause(1)).toList()
        val generation = session.learnedRetirementGeneration
        assertEquals(SharingMemoryStatus.PARTIAL, session.sharingMemory().status)
        val maintenance = LearnedMemoryMaintenance()
        val params = BacktrackParams(learnedMemoryBytes = 0L,
            learnedMemoryWork = 32_768L, learnedMemoryScratchBytes = 4096L)

        repeat(32) {
            val before = session.work
            maintenance.run(session, params, null, null)
            assertTrue(session.work - before <= 32_768L)
        }

        assertTrue(state.watches.byLit[4].backingData.size < posting)
        assertEquals(0, state.watches.byLit[4].size)
        assertEquals(permanentCount + 1, session.activeLearnedClauseCount)
        assertEquals(generation, session.learnedRetirementGeneration)
        assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
        val before = session.work
        maintenance.run(session, params, null, null)
        assertEquals(before, session.work)
        session.pinBool(4, false)
        assertEquals(true, session.boolValue(5))
        session.releaseRetainedState()
        assertEquals(SharingMemoryStatus.STALE, session.sharingMemory().status)
    }
}
