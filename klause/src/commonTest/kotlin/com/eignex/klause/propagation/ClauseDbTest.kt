package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClauseDbTest {

    @Test
    fun `live reasons should consume the ranked retention quota`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyArray()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 8)
            session.addLearnedClause(Clause(intArrayOf(1)), 1)

            repeat(8) { session.maintainCapWindow(2, 128L, 128L, { false }) { false } }

            assertEquals(2, session.activeLearnedClauseCount)
            assertTrue(session.isLearnedClause(0))
            assertTrue(!session.isLearnedClause(1))
        }
    }

    @Test
    fun `arithmetic rows should reduce the clause ranking quota`() {
        for (integer in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 1, arrayOf(IntDomain(0, 10)),
                arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))), nativeSat = false)
            if (integer) {
                val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
                    longArrayOf(1), intArrayOf(0), LinearOp.LE, 10), useLo = true))
                session.addLearnedIntegerRow(row, 9)
            } else session.addLearnedPb(longArrayOf(1, 1), intArrayOf(4, 6), 1, 9)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 8)

            repeat(8) { session.maintainCapWindow(2, 64L, 128L, { false }) { false } }

            assertEquals(2, session.activeLearnedClauseCount)
            assertTrue(!session.isLearnedClause(1))
            assertTrue(session.isLearnedClause(2))
        }
    }

    @Test
    fun `compaction should retain the arithmetic floor for pending clause ranking`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), emptyArray()), nativeSat = false)
        session.addLearnedPb(longArrayOf(1, 1), intArrayOf(4, 6), 1, 9)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 8)
        session.maintainCapWindow(2, 64L, 128L, { false }) { false }

        session.forgetLearnedClauses { index, _ -> index != 1 }
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 7)
        repeat(8) { session.maintainCapWindow(2, 64L, 128L, { false }) { false } }

        assertEquals(2, session.activeLearnedClauseCount)
        assertTrue(!session.isLearnedClause(1))
        assertTrue(session.isLearnedClause(2))
        assertIs<PseudoBooleanPropagator>(session.explanationState.factorAt(0))
    }

    @Test
    fun `general window retirement should disable propagation while retaining factor payload`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), arrayOf(Clause(intArrayOf(4, 6)))),
            nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(4, 6)), 9, permanent = true)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        val factor = session.problem.numFactors + 1

        session.maintainCapWindow(1, 128L, 128L, { false }) { false }
        session.pinBool(0, false)

        assertNull(session.boolValue(1))
        assertIs<ClausePropagator>(session.explanationState.factorAt(factor))
        assertEquals(1, session.activeLearnedClauseCount)
    }

    @Test
    fun `general window retirement should protect a live integer bound reason`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)), emptyArray()), nativeSat = false)
        val atom = session.explanationState.atomVarLe(0, 5)
        val index = session.learnedClauseCount
        session.addLearnedClause(Clause(intArrayOf(0, Lit.make(atom, true))), 9)
        session.addLearnedClause(Clause(intArrayOf(1)), 1, permanent = true)
        assertEquals(session.problem.numFactors + index, session.explanationState.intMaxReason[0])

        val retired = session.maintainCapWindow(1, 65_536L, 1_048_576L, { false }) { false }

        assertEquals(0, retired)
        assertTrue(session.isLearnedClause(index))
    }

    @Test
    fun `general window retirement should protect a historical integer bound reason`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)), emptyArray()), nativeSat = false)
        val atom = session.explanationState.atomVarLe(0, 5)
        val index = session.learnedClauseCount
        session.addLearnedClause(Clause(intArrayOf(0, Lit.make(atom, true))), 9)
        session.addLearnedClause(Clause(intArrayOf(1)), 1, permanent = true)
        val stronger = session.explanationState.atomVarLe(0, 3)
        session.addLearnedClause(Clause(intArrayOf(Lit.make(stronger, true))), 1, permanent = true)
        val factor = session.problem.numFactors + index
        val undo = session.explanationState.undo
        assertTrue(session.explanationState.intMaxReason[0] != factor)
        assertTrue((0 until undo.size).any { undo.tag[it] == 1 && undo.maxReason[it] == factor })

        val retired = session.maintainCapWindow(1, 65_536L, 1_048_576L, { false }) { false }

        assertEquals(0, retired)
        assertTrue(session.isLearnedClause(index))
    }

    @Test
    fun `general window retirement should protect a pending first assertion`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), arrayOf(Clause(intArrayOf(4, 6)))),
            nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(4, 6)), 9, permanent = true)
        val factor = session.explanationState.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        session.explanationState.deferLearnedAttachment(factor)

        val retired = session.maintainCapWindow(1, 128L, 128L, { false }) { false }
        session.pinBool(0, false)

        assertEquals(0, retired)
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `general window progress should resume across appended clauses`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), arrayOf(Clause(intArrayOf(4, 6)))),
            nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(4, 6)), 9, permanent = true)
        repeat(3) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        var retired = 0

        repeat(4) { attempt ->
            val before = session.work
            retired += session.maintainCapWindow(1, 64L, 128L, { false }) { false }
            assertTrue(session.work - before <= 64L)
            if (attempt == 0) session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        }

        assertEquals(4, retired)
        assertEquals(1, session.activeLearnedClauseCount)
    }

    @Test
    fun `general window cancellation at the debit should preserve the row`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), arrayOf(Clause(intArrayOf(4, 6)))),
            nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(4, 6)), 9, permanent = true)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        val before = session.work

        val retired = session.maintainCapWindow(1, 128L, 128L, { session.work - before >= 38L }) {
            error("cancelled policy callback")
        }

        assertEquals(0, retired)
        assertTrue(session.isLearnedClause(1))
        assertEquals(38L, session.work - before)
    }

    @Test
    fun `general retired slots should keep owned bytes until funded compaction`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), arrayOf(Clause(intArrayOf(4, 6)))),
            nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(4, 6)), 9, permanent = true)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        val before = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        session.maintainCapWindow(1, 128L, 128L, { false }) { false }
        val logical = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        session.forgetLearnedClauses { _, _ -> true }
        val compacted = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(before.getValue("ownedPrimitiveBytes"), logical.getValue("ownedPrimitiveBytes"))
        assertTrue(compacted.getValue("ownedPrimitiveBytes") < logical.getValue("ownedPrimitiveBytes"))
        assertEquals(1L, session.learnedLifecycle()["learningClauseRetired"])
    }

    @Test
    fun `releasing general window state should preserve charged totals`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), arrayOf(Clause(intArrayOf(4, 6)))),
            nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(4, 6)), 9, permanent = true)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        session.maintainCapWindow(1, 128L, 128L, { false }) { false }
        val before = session.learnedLifecycle()

        session.releaseRetainedState()

        assertEquals(before, session.learnedLifecycle())
        assertEquals(0, session.maintainCapWindow(1, 128L, 128L, { false }) { false })
    }

    @Test
    fun `reseeded general windows should accept fresh clause slots`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), arrayOf(Clause(intArrayOf(4, 6)))),
            nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(4, 6)), 9, permanent = true)
        repeat(3) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        session.maintainCapWindow(1, 64L, 128L, { false }) { false }
        session.seed(Assumptions.None)
        session.addLearnedClause(Clause(intArrayOf(4, 6)), 9, permanent = true)
        val index = session.learnedClauseCount
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)

        session.maintainCapWindow(1, 65_536L, 1_048_576L, { false }) { false }

        assertTrue(!session.isLearnedClause(index))
    }

    @Test
    fun `cancellation should retain native learning until component retraction permits resumption`() {
        for (nativeSat in listOf(false, true)) {
            var cancelled = false
            val session = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()),
                cancellation = Cancellation { cancelled }, propagationCancelFloor = 0, nativeSat = nativeSat)
            val shared = SearchSession(listOf(CpSearchComponent(session)), cancellation = Cancellation { cancelled })
            shared.initialize()
            shared.push(SearchDecision.Bool(4))
            cancelled = true

            session.addLearnedClause(Clause(intArrayOf(0)), 1)
            shared.popTo(0)
            cancelled = false
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.Bool(5)))

            assertEquals(1, session.learnedClauseCount)
            assertEquals(true, session.boolValue(0))
            assertEquals(true, shared.boolValue(0))
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
            val session = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()), nativeSat = nativeSat)
            session.pinBool(0, false)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 1)

            session.forgetLearnedClauses { _, _ -> false }
            assertEquals(1, session.learnedClauseCount)
            session.popToLevel(0)
            session.pinBool(2, true)
            session.forgetLearnedClauses { _, _ -> false }

            assertEquals(0, session.learnedClauseCount)
            assertEquals(1L, session.learnedLifecycle()["learningClauseRetired"])
        }
    }

    @Test
    fun `an asserting import should survive a preceding attachment conflict and reseeding`() {
        for (nativeSat in listOf(false, true)) {
            val session = PropagationSession(Problem(5, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(5)))), nativeSat = nativeSat)
            session.seed(Assumptions.None.withBool(0, true).withBool(1, false))
            session.importClause(SharedClause(intArrayOf(1, 2), longArrayOf(), 2), origin = 1)
            session.importClause(SharedClause(intArrayOf(4, 6), longArrayOf(), 2), origin = 2)
            assertIs<PropagationResult.Unsat>(session.pinBool(4, true))

            session.seed(Assumptions.None.withBool(0, false))

            assertEquals(true, session.boolValue(3))
        }
    }

    @Test
    fun `a nonunit assertion should be restored when its false root premise survives undo`() {
        for (nativeSat in listOf(false, true)) {
            val session = PropagationSession(Problem(3, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(1)))), nativeSat = nativeSat)
            session.pinBool(2, true)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.popToLevel(0)

            session.pinBool(2, false)

            assertEquals(true, session.boolValue(1))
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
    fun `admitted clauses should own their literal buffers on each native lane`() {
        for (nativeSat in listOf(false, true)) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = nativeSat)
            val clause = Clause(intArrayOf(0, 2))
            session.addLearnedClause(clause, 2)
            clause.literals.fill(1)

            session.pinBool(0, false)

            assertEquals(true, session.boolValue(1))
        }
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

    @Test
    fun `retirement should preserve permanent model blockers on each native lane`() {
        for (nativeSat in listOf(false, true)) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = nativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2, permanent = true)

            session.forgetLearnedClauses { _, _ -> false }

            assertEquals(1, session.learnedClauseCount)
            assertEquals(null, session.learnedLifecycle()["learningClauseRetired"])
        }
    }
}
