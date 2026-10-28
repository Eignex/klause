package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClauseDbTest {
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
}
