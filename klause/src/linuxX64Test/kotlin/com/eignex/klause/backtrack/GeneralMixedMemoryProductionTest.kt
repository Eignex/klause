package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.IntegerConflictRow
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.SharingMemoryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class GeneralMixedMemoryProductionTest {
    @Test
    @Suppress("FunctionNaming") // Native platform tests follow the common test naming convention.
    internal fun `production grants should release growing mixed tails while retaining every live owner`() {
        for (cap in listOf(4000, 20_000)) {
            val floor = Clause(intArrayOf(8, 10))
            val problem = Problem(6, 3, Array(3) { IntDomain(0, 10) },
                arrayOf(floor, Clause(intArrayOf(1)), Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10)))
            val session = PropagationSession(problem, nativeSat = false, pbLearning = true)
            val shared = SearchSession(emptyList())
            shared.learn(SearchExplanation(intArrayOf(8, 10)))
            session.sharedLearningMemory = shared::retainedLearningMemory
            session.sharedLearningPosition = shared::retainedLearningPosition
            repeat(cap - 3) { session.addLearnedClause(floor, 2, permanent = true) }
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            for (bound in listOf(8L, 6L)) {
                val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
                    longArrayOf(1), intArrayOf(0), LinearOp.LE, bound), useLo = true))
                session.addLearnedIntegerRow(row, 9)
            }
            val state = session.explanationState
            val rows = state.learned.store
            val events = assertNotNull(state.intEvents.overflow)
            val rowBytes = rows.primitiveBytes
            val rowReferences = rows.referenceCapacity
            val eventBytes = events.primitiveCapacityBytes
            val eventReferences = events.referenceCapacity
            val reason = assertNotNull(session.boolReasonClause(1)).toList()
            val integerReason = state.intMaxReason[0]
            val generation = session.learnedRetirementGeneration
            val params = BacktrackPresets.satOptimized(maxLearnedClauses = cap, inprocess = false)
            val unused = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(1, 2),
                longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 20L), useLo = true))
            repeat(3) {
                repeat(64) {
                    session.addLearnedClause(Clause(intArrayOf(4, 6)), 9)
                    session.addLearnedPb(longArrayOf(1, 1), intArrayOf(4, 6), 1L, 9)
                    session.addLearnedIntegerRow(unused, 9)
                }
                assertEquals(cap + 192, session.activeLearnedClauseCount)
                repeat(16) {
                    val before = session.work
                    forgetIfOverCap(session, params)
                    assertTrue(session.work - before <= params.learnedMemoryWork)
                }
                assertEquals(cap, session.activeLearnedClauseCount)
                assertEquals(rowBytes, rows.primitiveBytes)
                assertEquals(rowReferences, rows.referenceCapacity)
                assertEquals(2, assertNotNull(state.learned.integerRows).size)
                assertTrue(events.primitiveCapacityBytes <= eventBytes)
                assertTrue(events.referenceCapacity <= eventReferences)
                assertEquals(integerReason, state.intMaxReason[0])
                assertEquals(generation, session.learnedRetirementGeneration)
                assertEquals(1, shared.learnedClauseCount)
            }
            session.importClause(SharedClause(intArrayOf(0, 4), longArrayOf(), 2), origin = 7)
            repeat(16) {
                val before = session.work
                forgetIfOverCap(session, params)
                assertTrue(session.work - before <= params.learnedMemoryWork)
            }
            assertEquals(cap + 1, session.activeLearnedClauseCount)
            session.pinBool(4, false)
            assertEquals(true, session.boolValue(2))
            assertEquals(true, session.boolValue(5))
            session.popLast()
            session.pinIntAtMost(0, 4)
            session.popLast()
            assertEquals(IntDomain(0, 6), session.intDomain(0))
            assertEquals(integerReason, state.intMaxReason[0])
            assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
            session.releaseRetainedState()
            assertEquals(SharingMemoryStatus.STALE, session.sharingMemory().status)
            assertEquals(1, shared.learnedClauseCount)
            shared.releaseLearning()
            assertEquals(0, shared.learnedClauseCount)
        }
    }
}
