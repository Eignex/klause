package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClauseDbTest {
    @Test
    fun `cancellation should preserve admitted native learning for a later fixpoint`() {
        for (nativeSat in listOf(false, true)) {
            var cancelled = false
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()),
                cancellation = Cancellation { cancelled }, propagationCancelFloor = 0, nativeSat = nativeSat)
            cancelled = true

            session.addLearnedClause(Clause(intArrayOf(0)), 1)
            cancelled = false
            session.popToLevel(0)
            session.pinBool(1, true)

            assertEquals(1, session.learnedClauseCount)
            assertEquals(true, session.boolValue(0))
            assertEquals(1L, session.learnedLifecycle()["learningClauseConsumed"])
        }
    }

    @Test
    fun `a stored PB should wake after undo and keep its reason identity after forgetting`() {
        val native = PropagationSession(Problem(5, 1, arrayOf(IntDomain(0, 10)), emptyArray()), pbLearning = true)
        native.addLearnedClause(Clause(intArrayOf(Lit.make(4, true), Lit.make(4, false))), lbd = 4)
        native.addLearnedPb(LongArray(4) { 1L }, IntArray(4) { Lit.make(it, true) }, degree = 2L, lbd = 2)
        assertIs<PropagationResult.Implied>(native.pinBool(0, false))
        assertIs<PropagationResult.Implied>(native.pinBool(1, false))
        assertEquals(true, native.boolValue(2))
        assertEquals(1, native.explanationState.boolReason[2])
        native.popToLevel(0)

        native.forgetLearnedClauses { index, _ -> index == 1 }
        assertIs<PropagationResult.Implied>(native.pinBool(2, false))
        assertIs<PropagationResult.Implied>(native.pinBool(3, false))

        assertEquals(1, native.learnedClauseCount)
        assertEquals(true, native.boolValue(0))
        assertEquals(0, native.explanationState.boolReason[0])
        assertIs<PseudoBooleanPropagator>(native.explanationState.factorAt(0))
        assertEquals(0, native.exportGlueClauses(Int.MAX_VALUE, Int.MAX_VALUE).size)
    }

    @Test
    fun `an import should attach against root facts before the next decision fixpoint`() {
        for (nativeSat in listOf(false, true)) {
            val session = PropagationSession(Problem(3, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(Lit.make(0, false))))), nativeSat = nativeSat)
            session.importClause(SharedClause(intArrayOf(0, 2), longArrayOf(), 2), origin = 7)

            assertIs<PropagationResult.Implied>(session.pinBool(2, true))

            assertEquals(true, session.boolValue(1))
            assertEquals(1L, session.learnedLifecycle()["learningClauseAsserted"])
            assertEquals(1L, session.learnedLifecycle()["learningClauseConsumed"])
            assertEquals(0, session.exportGlueClauses(10, 10).size)
        }
    }

    @Test
    fun `a learned unit should be consumed once across retraction on each native lane`() {
        for (nativeSat in listOf(false, true)) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = nativeSat)
            session.pinBool(1, true)
            session.addLearnedClause(Clause(intArrayOf(0)), 1)
            session.popToLevel(0)

            assertIs<PropagationResult.Implied>(session.pinBool(1, false))

            assertEquals(true, session.boolValue(0))
            assertEquals(mapOf("learningClauseConstructed" to 1L, "learningClauseAdmitted" to 1L,
                "learningClauseAsserted" to 1L, "learningClauseConsumed" to 1L), session.learnedLifecycle())
        }
    }

    @Test
    fun `retirement should retain a live clause reason until its pin retracts`() {
        for (nativeSat in listOf(false, true)) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = nativeSat)
            session.pinBool(1, true)
            session.addLearnedClause(Clause(intArrayOf(0)), 1)

            session.forgetLearnedClauses { _, _ -> false }
            assertEquals(1, session.learnedClauseCount)
            session.popToLevel(0)
            session.forgetLearnedClauses { _, _ -> false }

            assertEquals(0, session.learnedClauseCount)
            assertEquals(1L, session.learnedLifecycle()["learningClauseRetired"])
        }
    }

    @Test
    fun `compaction should retain and remap an integer reason saved in undo history`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))))
        session.addLearnedClause(Clause(intArrayOf(0, 1)), 4)
        val row = requireNotNull(IntegerConflictRow.source(
            LinearPropagator(intArrayOf(), intArrayOf(0),
                longArrayOf(1), intArrayOf(0), LinearOp.LE, 8), useLo = true))
        session.addLearnedIntegerRow(row, 2)
        session.pinIntAtMost(0, 4)

        session.forgetLearnedClauses { _, _ -> false }
        session.popLast()

        assertEquals(IntDomain(0, 8), session.intDomain(0))
        assertEquals(1, session.explanationState.intMaxReason[0])
        assertIs<LearnedIntegerPropagator>(session.explanationState.factorAt(1))
        assertEquals(1L, session.learnedLifecycle()["learningIntegerConsumed"])
        assertEquals(1L, session.learnedLifecycle()["learningClauseRetired"])
    }

    @Test
    fun `admitted PB rows should own their buffers`() {
        val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()))
        val weights = longArrayOf(1, 1)
        val literals = intArrayOf(0, 2)
        session.addLearnedPb(weights, literals, 1, 2)
        weights.fill(0)
        literals.fill(1)

        session.pinBool(0, false)

        assertEquals(true, session.boolValue(1))
        assertEquals(1L, session.learnedLifecycle()["learningPbConsumed"])
        assertEquals(0, session.exportGlueClauses(10, 10).size)
    }

    @Test
    fun `a PB assertion should wake after its deduction is retracted`() {
        val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()))
        session.pinBool(1, true)
        session.addLearnedPb(longArrayOf(1), intArrayOf(0), 1, 1)
        session.popToLevel(0)

        session.pinBool(1, false)

        assertEquals(true, session.boolValue(0))
        assertEquals(1L, session.learnedLifecycle()["learningPbConsumed"])
    }

    @Test
    fun `an integer assertion should wake after its deduction is retracted`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))))
        session.pinBool(0, true)
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 8), useLo = true))
        session.addLearnedIntegerRow(row, 1)
        session.popToLevel(0)

        session.pinBool(0, false)

        assertEquals(IntDomain(0, 8), session.intDomain(0))
        assertEquals(1L, session.learnedLifecycle()["learningIntegerConsumed"])
    }

    @Test
    fun `an imported implication should retain its assumption premise on each native lane`() {
        for (nativeSat in listOf(false, true)) {
            val session = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()), nativeSat = nativeSat)
            session.seed(Assumptions.None.withBool(0, true))
            session.importClause(SharedClause(intArrayOf(1, 2), longArrayOf(), 2), origin = 3)
            session.pinBool(2, true)
            assertEquals(true, session.boolValue(1))

            session.seed(Assumptions.None.withBool(0, false))

            assertEquals(null, session.boolValue(1))
            assertEquals(0, session.exportGlueClauses(10, 10).size)
        }
    }

    @Test
    fun `scoped permanent clauses should stay out of global exports when requested`() {
        for (nativeSat in listOf(false, true)) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = nativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2, permanent = true)

            assertEquals(0, session.exportGlueClauses(10, 10, skipPermanent = true).size)
            assertEquals(1, session.exportGlueClauses(10, 10).size)
        }
    }
}
