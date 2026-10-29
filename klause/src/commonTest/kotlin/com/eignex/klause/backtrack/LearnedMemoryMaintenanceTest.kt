package com.eignex.klause.backtrack

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearnedMemoryMaintenanceTest {
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
