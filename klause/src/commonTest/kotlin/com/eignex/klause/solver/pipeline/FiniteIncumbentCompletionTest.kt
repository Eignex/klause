package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.presolve.ObjectiveAdjustment
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FiniteIncumbentCompletionTest {
    @Test
    fun `original checking should preserve objective sense scale and presolve adjustment`() {
        for (sense in ObjectiveSense.entries) {
            val source = LinearSourceProblem(listOf(LinearSourceVariable("r", false,
                SemanticRational.ZERO, SemanticRational.ONE)), emptyList(),
                LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                    constant = SemanticRational.ONE, sense = sense))
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE))
            val original = assertNotNull(invocation.linearSource)
            val model = original.view.model
            val half = BigFraction.ofLong(2).reciprocal()
            val point = Sample(booleanArrayOf(), LongArray(model.numIntVars), doubleArrayOf(0.5),
                exactReals = listOf(half))
            val adjusted = if (sense == ObjectiveSense.MINIMIZE) half else half.negated()
            val mapping = SourceMapping(model, model, TransformationGuarantees.IDENTITY,
                objectiveAdjustment = ObjectiveAdjustment(BigFraction.ofLong(2), adjusted))
            val check = original.incumbentCompletionCheck(mapping)

            assertTrue(check(point, adjusted, Cancellation.Never))
            assertFalse(check(point, adjusted + BigFraction.ONE, Cancellation.Never))
        }
    }

    @Test
    fun `original source bounds should reject an exact point accepted by a relaxed working view`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("r", false,
            SemanticRational.ZERO, SemanticRational.ONE)), emptyList(),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))))
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED))
        val original = assertNotNull(invocation.linearSource)
        val two = BigFraction.ofLong(2)
        val point = Sample(booleanArrayOf(), LongArray(original.view.model.numIntVars), doubleArrayOf(2.0),
            exactReals = listOf(two))

        assertFalse(original.incumbentCompletionCheck(null)(point, two, Cancellation.Never))
    }

    @Test
    fun `original checking should charge reconstruction and stop on cancellation`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("r", false,
            SemanticRational.ZERO, SemanticRational.ONE)), emptyList(),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))))
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED))
        val original = assertNotNull(invocation.linearSource)
        val half = BigFraction.ofLong(2).reciprocal()
        val point = Sample(booleanArrayOf(), LongArray(original.view.model.numIntVars), doubleArrayOf(0.5),
            exactReals = listOf(half))
        var work = 0L
        val token = object : Cancellation {
            override fun isCancelled() = work > 0L
            override fun workMeter() = WorkMeter { work += it }
        }

        assertFalse(original.incumbentCompletionCheck(null)(point, half, token))
        assertTrue(work > 0L)
    }
}
