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
class ClauseDbRankingTest(private val cap: Int, private val nativeSat: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "cap={0}, nativeSat={1}")
        fun parameters(): List<Array<Any>> = listOf(
            arrayOf<Any>(4_000, false), arrayOf<Any>(4_000, true),
            arrayOf<Any>(20_000, false), arrayOf<Any>(20_000, true),
        )
    }

    @Test
    fun `production caps should rank locals without a permanent floor`() {
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
