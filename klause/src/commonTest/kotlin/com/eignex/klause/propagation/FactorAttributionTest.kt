package com.eignex.klause.propagation

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FactorAttributionTest {
    @Test
    fun `retiring a clause should release its aliased diagnostic reason`() {
        val attribution = FactorAttribution(2)
        val clause = ClausePropagator(intArrayOf(0, 1), intArrayOf(), intArrayOf(0, 2))
        val other = intArrayOf(4)
        val family = 1 shl FactorFamily.Table.ordinal
        attribution.remember(clause.literals, family)
        attribution.remember(other, family)

        attribution.retire(clause)

        assertEquals(1L, attribution.snapshot()["factorTrackedReasons"])
        assertEquals(1 shl FactorFamily.Unattributed.ordinal, attribution.reasonFamily(clause.literals))
        assertEquals(family, attribution.reasonFamily(other))
    }

    @Test
    fun `ordinary table conflicts carry family attribution into consumed learning`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1)),
            Table(intArrayOf(0, 1), longArrayOf(0, 1, 1, 0)),
        )).bake()
        for (refinement in listOf(false, true)) {
            val result = BacktrackSolver(problem).solve(BacktrackParams(
                randomSeed = 1L, maxDecisions = 8L, lpConfig = LpConfig.OFF,
                propagationAttribution = true, historicalTableReasons = refinement,
            ))

            assertIs<SolveResult.Unsat>(result)
            assertTrue(result.stats.search.learning.getValue("factorTableUipReasons") > 0)
            assertTrue(result.stats.search.learning.getValue("factorTableConsumed") > 0)
        }
    }

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
