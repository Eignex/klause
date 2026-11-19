package com.eignex.klause.cli

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.optimizeLinear
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.util.numbers.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@RunWith(Parameterized::class)
class LinearOptimizationOutputTest(
    private val result: LinearOptimizationResult,
    private val expected: String,
    private val exhausted: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{1} budgetExhausted={2}")
        fun terminals(): List<Array<Any>> {
            val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
            val scope = SourceScope(SourceRevision("terminal-output", "exact-affine"),
                objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
            return listOf(Verdict.OPTIMAL, Verdict.UNSATISFIABLE, Verdict.UNBOUNDED).flatMap { verdict ->
                val premises = buildMap {
                    if (verdict != Verdict.UNBOUNDED) {
                        put("lower", SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                            BigFraction.ZERO)))
                    }
                    if (verdict == Verdict.UNSATISFIABLE) {
                        put("upper", SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE),
                            BigFraction.MINUS_ONE)))
                    }
                }
                val context = SourceProofContext(scope, setOf(x), premises, mapOf(x to BigFraction.ONE))
                val result = OpenTheoryPipeline.optimizeLinear(context)
                listOf(false, true).map { exhausted -> arrayOf(result, verdict.name, exhausted) }
            }
        }
    }

    @Test
    fun `a late exhausted budget should preserve a completed linear verdict`() {
        var completed: Verdict? = null
        var retained: LinearOptimizationResult? = null
        var metadata: VerdictContext? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean = true
            override fun onVerdictContext(context: VerdictContext) {
                metadata = context
            }
            override fun onLinearResult(result: LinearOptimizationResult) {
                retained = result
            }
            override fun onComplete(verdict: Verdict) {
                completed = verdict
            }
        }

        reportLinearOptimization(result, { "point" }, output, ArithmeticPublication(output), false, 0L, true,
            budgetExhausted = { exhausted })

        assertEquals(expected, completed?.name)
        assertSame(result, retained)
        assertEquals(exhausted, assertNotNull(metadata).budgetExhausted)
        assertEquals(result.reason, assertNotNull(metadata).terminationReason)
    }
}
