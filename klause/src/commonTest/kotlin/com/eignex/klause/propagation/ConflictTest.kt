package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictTest {
    @Test
    fun `factor core extraction follows remapped learned reasons`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))), lbd = 2)
        val first = state.addLearnedClause(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))), lbd = 2)
        val second = state.addLearnedClause(Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))), lbd = 2)
        state.boolReason[0] = 0
        state.boolReason[1] = first
        state.boolReason[2] = second
        state.conflictSeedFactors.add(second)
        state.extractConflictFactors()

        state.forgetLearnedClauses { index, _ -> index != 0 }
        state.conflictSeedFactors.clear()
        state.conflictSeedFactors.add(state.boolReason[2])
        val core = state.extractConflictFactors()

        assertEquals(setOf(0, 1, 2), core.toSet())
    }
}
