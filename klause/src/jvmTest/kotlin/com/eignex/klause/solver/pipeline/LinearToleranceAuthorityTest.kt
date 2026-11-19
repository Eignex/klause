package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class LinearToleranceAuthorityTest(private val sense: ObjectiveSense) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun directions(): List<ObjectiveSense> = ObjectiveSense.entries
    }

    private val slack = BigFraction.ofLong(10_000_000).reciprocal()
    private val source = LinearSourceProblem(
        listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
            LinearSourceVariable("r", false, SemanticRational.ONE, SemanticRational.ofLong(2))),
        emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(1, SemanticRational.ONE)), sense = sense),
        SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
            absolute = SemanticRational.of(slack.num, slack.den))).resolve(),
    )

    @Test
    fun `separate authority should retain the ordinary working tolerance`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE,
            linearToleranceEnvelope = true))
        val prepared = assertNotNull(invocation.linearSource)

        assertSame(source.semantics, prepared.view.model.semantics)
        assertFalse(prepared.acceptedModelExact)
        assertNotNull(prepared.toleranceAuthority)
    }

    @Test
    fun `accepted set bounds should require the separate authority scope`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE,
            linearToleranceEnvelope = true))
        val prepared = assertNotNull(invocation.linearSource)
        val producer = assertNotNull(prepared.toleranceAuthority)
        val nominal = prepared.view.model
        val objective = assertNotNull(invocation.selection.finiteObjective)
        val context = assertNotNull(producer.capture(nominal, objective, ExecutionControl.Never))
        val evidence = LinearAuthority(SourceLpBudget(), retainLp = true).use {
            assertNotNull(it.advance(context, ExecutionControl.Never).bound)
        }
        val foreign = SourceEvidence(evidence.scope.copy(revision = evidence.scope.revision.copy(identity = "foreign")),
            evidence.claim, evidence.derivation)

        val accepted = assertNotNull(prepared.qualifyBound(evidence, true, finiteObjectiveBound(evidence, null)))
        val ordinary = assertNotNull(prepared.qualifyBound(foreign, true, finiteObjectiveBound(foreign, null)))

        assertEquals(if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE - slack else BigFraction.ofLong(2) + slack,
            accepted.value)
        assertTrue(accepted.toleranceEnvelope)
        assertFalse(ordinary.toleranceEnvelope)
    }

    @Test
    fun `a changed objective or opaque reconstruction should decline shared authority`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE,
            linearToleranceEnvelope = true))
        val prepared = assertNotNull(invocation.linearSource)
        val producer = assertNotNull(prepared.toleranceAuthority)
        val nominal = prepared.view.model
        val objective = assertNotNull(invocation.selection.finiteObjective)
        val identity = SourceMapping(nominal, nominal, TransformationGuarantees.IDENTITY)
        val opaque = SourceMapping(nominal, nominal, TransformationGuarantees.IDENTITY, lift = { it })

        assertTrue(producer.compatible(identity, nominal, objective))
        assertFalse(producer.compatible(identity, nominal, objective.copy(constant = objective.constant + 1L)))
        assertFalse(producer.compatible(opaque, nominal, objective))
    }
}
