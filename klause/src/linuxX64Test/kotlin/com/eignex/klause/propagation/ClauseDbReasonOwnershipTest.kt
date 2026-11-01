package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ClauseDbReasonOwnershipTest {

    @Test
    @Suppress("FunctionNaming") // Native platform tests follow the common test naming convention.
    internal fun `Boolean retirement should progress within small grants at production sizes`() {
        for ((cap, native) in listOf(4_000 to false, 4_000 to true, 20_000 to false, 20_000 to true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(4, 6)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            val clause = Clause(intArrayOf(0, 2))
            repeat(cap) { session.addLearnedClause(clause, 9) }

            repeat(cap) {
                if (session.activeLearnedClauseCount == 0) return@repeat
                val before = session.work
                session.maintainCapWindow(0, 256L, 64L, { false }) { false }
                assertTrue(session.work - before <= 256L)
            }

            assertEquals(0, session.activeLearnedClauseCount)
            assertEquals(cap.toLong(), session.learnedLifecycle()["learningClauseRetired"])
        }
    }
}
