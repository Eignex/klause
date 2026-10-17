package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceExecutionPolicyTest {
    @Test
    fun `LP ceilings should capture caller overrides independently`() {
        val overrides = mutableMapOf(LpTechnique.CUTS to false)
        val policy = SourceExecutionPolicy(lpCeiling = LpConfig(LpEmphasis.AGGRESSIVE, overrides))

        overrides[LpTechnique.CUTS] = true

        assertFalse(assertNotNull(policy.lpCeiling).resolved(LpTechnique.CUTS))
    }

    @Test
    fun `deferred finite policy should retain containing controls and application hooks`() {
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool) (assert b)").source,
            ExecutionOperation.SATISFY,
            FiniteEngine.BACKTRACK,
        )
        )
        val budget = NodeBudget(7)
        val stop = Cancellation { true }
        val events: (SearchEvent) -> Unit = {}
        val policy = SourceExecutionPolicy(
            lpCeiling = LpConfig.OFF,
            portfolioArms = 3,
            zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
        )
        val request = SourceExecutionRequest(
            invocation,
            policy,
            ExecutionOptions(ExecutionOption.seed(19L)),
            nodeBudget = budget,
            cancellation = stop,
            finiteRequest = { shape ->
                SourceExecutionPolicy().finiteRequest(shape, FiniteEngine.BACKTRACK, onEvent = events)
            },
        )

        val finite = assertNotNull(request.finiteRequest).invoke(invocation.finiteShape())

        assertSame(policy, request.policy)
        assertSame(budget, finite.nodeBudget)
        assertSame(events, finite.onEvent)
        assertTrue(finite.cancellation.isCancelled())
        assertEquals(19L, finite.randomSeed)
        assertEquals(3, finite.defaultArms)
        assertEquals(3, finite.executionOptions.reader()[ExecutionOption.arms])
        assertEquals(LpEmphasis.OFF, finite.lpConfig.emphasis)
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, finite.zeroObjectivePricing)
        assertEquals(0L, budget.spent)
    }

    @Test
    fun `linear source preparation should receive typed presolve tuning`() {
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SmtLib.parse("(declare-const x Real) (assert (> x 0))").source,
            ExecutionOperation.SATISFY,
            FiniteEngine.BACKTRACK,
        )
        )

        val request = SourceExecutionRequest(
            invocation, SourceExecutionPolicy(),
            ExecutionOptions(
            ExecutionOption.affinePivotOrder(AffinePivotOrder.STABLE_ID),
            ExecutionOption.presolveMaxRounds(2),
        )
        )

        val preparation = assertNotNull(request.presolveConfig)
        assertEquals(AffinePivotOrder.STABLE_ID, preparation.affinePivotOrder)
        assertEquals(2, preparation.maxRounds)
        assertFalse(request.policy.explicitPresolve)
    }

    @Test
    fun `presolve effort tuning should preserve implicit local search emphasis`() {
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool) (assert b)").source,
            ExecutionOperation.SATISFY,
            FiniteEngine.LOCAL_SEARCH,
        )
        )
        val request = SourceExecutionRequest(
            invocation, SourceExecutionPolicy(),
            ExecutionOptions(
            ExecutionOption.presolveMaxRounds(2),
        ),
            presolveConfig = PresolveConfig.DEFAULT
        )

        val finite = assertNotNull(request.finiteRequest).invoke(invocation.finiteShape())

        assertEquals(2, finite.presolveConfig.maxRounds)
        assertFalse(finite.explicitPresolveConfig)
        assertEquals(null, finite.executionOptions.reader()[ExecutionOption.presolveMaxRounds])
    }
}
