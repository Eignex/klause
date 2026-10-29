package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FactorAttributionTest {
    @Test
    fun `reason attribution evicts bounded metadata without changing premises`() {
        val attribution = FactorAttribution(2)
        val first = intArrayOf(0, 2)
        attribution.remember(first, 1 shl FactorFamily.Table.ordinal)
        attribution.remember(intArrayOf(4), 1 shl FactorFamily.Linear.ordinal)
        attribution.remember(intArrayOf(6), 1 shl FactorFamily.Clause.ordinal)

        assertEquals(2L, attribution.snapshot()["factorTrackedReasons"])
        assertEquals(1 shl FactorFamily.Unattributed.ordinal, attribution.reasonFamily(first))
        assertEquals(listOf(0, 2), first.toList())
    }

    @Test
    fun `attributed learning distinguishes immediate assertions from later sibling use`() {
        for (nativeSat in listOf(false, true)) {
            val session = PropagationSession(PropagationProblem(Problem(3, 0, emptyArray(), emptyArray())),
                nativeSat = nativeSat, attribution = true)
            val family = 1 shl FactorFamily.Clause.ordinal
            session.pinBool(0, false)
            session.withAttribution(family) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 2) }
            assertEquals(true, session.boolValue(1))
            assertEquals(null, session.learnedLifecycle()["factorClauseLaterUseEvents"])
            session.popToLevel(0)

            assertIs<PropagationResult.Implied>(session.pinBool(1, false))

            assertEquals(true, session.boolValue(0))
            assertEquals(1L, session.learnedLifecycle()["factorClauseConsumed"])
            assertTrue(session.learnedLifecycle().getValue("factorClauseLaterUseEvents") > 0)
        }
    }

    @Test
    fun `attribution preserves native and general first uip learning`() {
        for (nativeSat in listOf(false, true)) {
            val problem = Problem(3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(1, 2)), Clause(intArrayOf(3, 4)), Clause(intArrayOf(1, 3, 5))))
            val session = PropagationSession(PropagationProblem(problem), nativeSat = nativeSat, attribution = true)

            val conflict = assertIs<PropagationResult.Unsat>(session.pinBool(0, true))
            val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)

            assertEquals(setOf(Lit.make(0, false)), learned.literals.toSet())
            assertTrue(session.learnedLifecycle().getValue("factorClauseUipReasons") > 0)
        }
    }
}
