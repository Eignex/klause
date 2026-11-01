package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ClauseDbRankingTest {
    @Test
    @Suppress("FunctionNaming") // Native platform tests follow the common test naming convention.
    internal fun `production caps should rank locals without a permanent floor`() {
        for ((cap, nativeSat) in listOf(4_000 to false, 4_000 to true, 20_000 to false, 20_000 to true)) {
            val session = PropagationSession(Problem(3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(1, 2)), Clause(intArrayOf(3, 4)),
            )), nativeSat = nativeSat)
            val clause = Clause(intArrayOf(1, 3, 4))
            repeat(cap) { session.addLearnedClause(clause, 9) }
            session.addLearnedClause(clause, 8)
            val params = BacktrackParams(maxLearnedClauses = cap, tieredLearnedDb = true)

            repeat(64) {
                if (session.activeLearnedClauseCount <= cap) return@repeat
                val before = session.work
                forgetIfOverCap(session, params)
                assertTrue(session.work - before <= 65_536L)
            }

            assertEquals(cap, session.activeLearnedClauseCount)
            assertFalse(session.isLearnedClause(cap - 1))
            assertTrue(session.isLearnedClause(cap))
        }
    }
}
