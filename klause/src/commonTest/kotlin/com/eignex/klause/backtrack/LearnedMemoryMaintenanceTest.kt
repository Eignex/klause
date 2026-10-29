package com.eignex.klause.backtrack

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearnedMemoryMaintenanceTest {
    @Test
    fun `cancelled optional maintenance should defer until native ownership changes`() {
        var limited = false
        var sliceStart = 0L
        lateinit var session: PropagationSession
        session = PropagationSession(Problem(2, 0, emptyArray(), emptyList()),
            Cancellation { limited && session.work - sliceStart >= 1L })
        val maintenance = LearnedMemoryMaintenance()
        val params = BacktrackParams(learnedMemoryBytes = 0L, learnedMemoryWork = 100_000L)
        limited = true
        sliceStart = session.work
        maintenance.run(session, params, null, null)
        val afterCancelled = session.work
        sliceStart = afterCancelled

        maintenance.run(session, params, null, null)

        assertEquals(afterCancelled, session.work)
        session.reclaimLearnedMemory(0L, 100_000L)
        assertTrue(session.work > afterCancelled)
    }

    @Test
    fun `shared retention growth should invalidate unchanged native maintenance`() {
        val session = PropagationSession(Problem(2, 0, emptyArray(), emptyList()))
        val shared = SearchSession(emptyList())
        session.sharedLearningMemory = shared::retainedLearningMemory
        session.sharedLearningPosition = shared::retainedLearningPosition
        val maintenance = LearnedMemoryMaintenance()
        val params = BacktrackParams(learnedMemoryBytes = 0L, learnedMemoryWork = 100_000L)
        maintenance.run(session, params, null, null)
        val before = session.work
        val nativePosition = session.ownershipWork
        shared.learn(SearchExplanation(intArrayOf(0, 2)))
        assertEquals(nativePosition, session.ownershipWork)

        maintenance.run(session, params, null, null)

        assertTrue(session.work > before)
        val after = session.work
        maintenance.run(session, params, null, null)
        assertEquals(after, session.work)
    }

    @Test
    fun `read only observation should not renew unchanged maintenance across slices`() {
        val session = PropagationSession(Problem(2, 0, emptyArray(), emptyList()))
        val maintenance = LearnedMemoryMaintenance()
        val params = BacktrackParams(learnedMemoryBytes = 0L, learnedMemoryWork = 100_000L)
        maintenance.run(session, params, null, null)
        session.sharingMemory()
        val afterObservation = session.work

        maintenance.run(session, params, null, null)

        assertEquals(afterObservation, session.work)
    }

    @Test
    fun `private sharing capacity growth should invalidate unchanged native maintenance`() {
        val session = PropagationSession(Problem(2, 0, emptyArray(), emptyList()))
        val owner = Any()
        var bytes = 64L
        session.retainedSharingSize = { bytes }
        session.retainedSharingMemory = { it.owned(owner, bytes, protected = true) }
        val maintenance = LearnedMemoryMaintenance()
        val params = BacktrackParams(learnedMemoryBytes = 0L, learnedMemoryWork = 100_000L)
        maintenance.run(session, params, null, null)
        val before = session.work
        bytes = 128L

        maintenance.run(session, params, null, null)

        assertTrue(session.work > before)
    }
}
