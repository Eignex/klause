package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class ClauseDbRankingTest(private val cap: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "cap={0}")
        fun parameters(): List<Array<Int>> = listOf(arrayOf(4_000), arrayOf(20_000))
    }

    @Test
    fun `general production caps should rank locals without a permanent floor`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(3, 4)),
        )), nativeSat = false)
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
