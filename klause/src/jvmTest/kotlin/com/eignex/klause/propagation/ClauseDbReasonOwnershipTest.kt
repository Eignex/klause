package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class ClauseDbReasonOwnershipTest(private val cap: Int, private val native: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "cap={0}, native={1}")
        fun parameters(): List<Array<Any>> = listOf(
            arrayOf<Any>(4_000, false), arrayOf<Any>(4_000, true),
            arrayOf<Any>(20_000, false), arrayOf<Any>(20_000, true),
        )
    }

    @Test
    fun `Boolean retirement should progress within small grants at production sizes`() {
        val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = native)
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
