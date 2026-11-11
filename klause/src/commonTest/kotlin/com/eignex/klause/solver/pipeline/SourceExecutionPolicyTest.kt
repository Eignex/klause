package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.relaxation.LpSourcePreparation
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceExecutionPolicyTest {
    @Test
    fun `captured LP ceilings should retain source preparation policy`() {
        for (mode in LpSourcePreparation.entries) {
            for (applicationDefault in listOf(false, true)) {
                val config = LpConfig(sourcePreparation = mode)
                val policy = if (applicationDefault) SourceExecutionPolicy(portfolioLpCeiling = config)
                else SourceExecutionPolicy(lpCeiling = config)

                val resolved = policy.lpConfig(FiniteEngine.BACKTRACK)

                assertEquals(mode, resolved.sourcePreparation)
            }
        }
    }

    @Test
    fun `fixed execution should retain an explicit disabled LP preparation policy`() {
        val policy = SourceExecutionPolicy(lpCeiling = LpConfig.parse("off,preparation=disabled"))

        val resolved = policy.lpConfig(FiniteEngine.FIXED)

        assertEquals(LpSourcePreparation.DISABLED, resolved.sourcePreparation)
    }

    @Test
    fun `native formula sources should accept disabled preparation`() {
        val sources = listOf(
            "(declare-const x Real) (assert (= (* x x) 2))",
            "(declare-const x Int) (assert (= (* x x) 2))",
            "(declare-const x Real) (assert (= (sin x) 0))",
        )
        for (source in sources) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SmtLib.parse(source).source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            ))

            val request = SourceExecutionRequest(invocation, ExecutionOptions(), presolveConfig = PresolveConfig.NONE)

            assertSame(PresolveConfig.NONE, request.presolveConfig)
        }
    }

    @Test
    fun `native formula sources should explain unavailable individual reductions`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const x Int) (assert (= (* x x) 2))").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))

        val failure = assertFailsWith<PipelineConfigException> {
            SourceExecutionRequest(invocation, ExecutionOptions(),
                presolveConfig = PresolveConfig.parse("off,+probe-open"))
        }

        assertTrue("canonical factor model" in assertNotNull(failure.message))
    }

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

    @Test
    fun `a positive source tolerance should select numerical LP deductions`() {
        val request = finiteRequest(SourceTolerance(absolute = SemanticRational.ONE))

        assertTrue(request.lpSolveContext.numericalDeductions)
    }

    @Test
    fun `a zero or undeclared source tolerance should certify LP deductions exactly`() {
        for (tolerance in listOf(SourceTolerance(), null)) {
            val request = finiteRequest(tolerance)

            assertFalse(request.lpSolveContext.numericalDeductions)
        }
    }

    private fun finiteRequest(tolerance: SourceTolerance?): FiniteSolveRequest {
        val source = Problem(
            0, 1, arrayOf(IntDomain(0, 3)), arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)),
            semantics = SourceSemantics(SourceFormat.IR, feasibilityTolerance = tolerance).resolve(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK),
        )
        return SourceExecutionPolicy().finiteRequest(invocation.finiteShape(), FiniteEngine.BACKTRACK)
    }
}
