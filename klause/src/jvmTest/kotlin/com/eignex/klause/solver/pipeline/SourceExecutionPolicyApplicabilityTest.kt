package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(Parameterized::class)
class SourceExecutionPolicyApplicabilityTest(private val sourceText: String?) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}")
        fun sources(): List<String?> = listOf(
            "(declare-const x Real) (assert (> x 0))",
            "(declare-const x Real) (assert (= (* x x) 2))",
            "(declare-const x Int) (assert (= (* x x) 2))",
            "(declare-const x Real) (assert (= (sin x) 0))",
            null,
        )
    }

    @Test
    fun `native sources should reject explicit finite LP policy before constructing workers`() {
        val source = sourceText?.let { SmtLib.parse(it).source } ?: SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", true)), emptyList()), ProblemSettings(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
        var calls = 0

        assertFailsWith<PipelineConfigException> {
            SourceExecutionRequest(invocation, SourceExecutionPolicy(lpCeiling = LpConfig.OFF),
                finiteRequest = { shape ->
                    calls++
                    SourceExecutionPolicy().finiteRequest(shape, invocation.request.engine)
                })
        }
        assertEquals(0, calls)
    }

    @Test
    fun `native sources should reject explicit pricing even when it equals the default`() {
        val source = sourceText?.let { SmtLib.parse(it).source } ?: SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", true)), emptyList()), ProblemSettings(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        assertFailsWith<PipelineConfigException> {
            SourceExecutionRequest(invocation, SourceExecutionPolicy(
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
            ))
        }
    }

    @Test
    fun `native presolve tuning should require linear source preparation`() {
        val source = sourceText?.let { SmtLib.parse(it).source } ?: SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", true)), emptyList()), ProblemSettings(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
        val options = ExecutionOptions(ExecutionOption.presolveMaxRounds(2))

        if (SourceSetup.executionKind(invocation) == SourceExecutionKind.LINEAR) {
            assertEquals(2, SourceExecutionRequest(invocation, options).presolveConfig?.maxRounds)
        } else {
            assertFailsWith<PipelineConfigException> { SourceExecutionRequest(invocation, options) }
        }
    }
}
