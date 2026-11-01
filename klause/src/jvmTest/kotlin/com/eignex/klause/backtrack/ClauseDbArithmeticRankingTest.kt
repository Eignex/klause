package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.IntegerConflictRow
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.factorAt
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class ClauseDbArithmeticRankingTest(private val cap: Int, private val integer: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "cap={0}, integer={1}")
        fun parameters(): List<Array<Any>> = listOf(
            arrayOf<Any>(4_000, false), arrayOf<Any>(4_000, true),
            arrayOf<Any>(20_000, false), arrayOf<Any>(20_000, true),
        )
    }

    @Test
    fun `production caps should rank arithmetic rows with local clauses`() {
        val session = PropagationSession(Problem(3, 1, arrayOf(IntDomain(0, 10)), arrayOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(3, 4)),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10),
        )), nativeSat = false)
        val clause = Clause(intArrayOf(1, 3, 4))
        repeat(cap) { session.addLearnedClause(clause, 9) }
        if (integer) {
            val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
                longArrayOf(1), intArrayOf(0), LinearOp.LE, 10), useLo = true))
            session.addLearnedIntegerRow(row, 10)
        } else session.addLearnedPb(longArrayOf(1, 1, 1), clause.literals, 1, 10)
        val params = BacktrackParams(maxLearnedClauses = cap, tieredLearnedDb = true)

        repeat(64) {
            if (session.activeLearnedClauseCount <= cap) return@repeat
            val before = session.work
            forgetIfOverCap(session, params)
            assertTrue(session.work - before <= 65_536L)
        }

        assertEquals(cap, session.activeLearnedClauseCount)
        assertTrue(session.isLearnedClause(cap - 1))
        assertEquals(NoPropagator, session.explanationState.factorAt(session.problem.numFactors + cap))
    }
}
