package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.IntegerConflictRow
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.factorAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ClauseDbArithmeticRankingTest {

    @Test
    @Suppress("FunctionNaming") // Native platform tests follow the common test naming convention.
    internal fun `production caps should rank arithmetic rows with local clauses`() {
        for ((cap, integer) in listOf(4_000 to false, 4_000 to true, 20_000 to false, 20_000 to true)) {
            val boolFactors = arrayOf<Factor>(
                Clause(intArrayOf(1, 2)), Clause(intArrayOf(3, 4)),
            )
            val problem = Problem(3, if (integer) 1 else 0,
                if (integer) arrayOf(IntDomain(0, 10)) else emptyArray(),
                if (integer) boolFactors + Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10) else boolFactors)
            val session = PropagationSession(problem, nativeSat = false)
            val clause = Clause(intArrayOf(1, 3, 4))
            repeat(cap - 4) { session.addLearnedClause(clause, 9, permanent = true) }
            repeat(4) { session.addLearnedClause(clause, 9) }
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
}
