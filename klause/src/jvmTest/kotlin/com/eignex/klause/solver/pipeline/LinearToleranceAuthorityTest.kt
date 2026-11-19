package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.presolve.ObjectiveAdjustment
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
import kotlin.test.assertNull
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
    fun `omitted source controls should select the compatible accepted set authority`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE))
        val request = SourceExecutionRequest(invocation).withValidationCancellation(ExecutionControl.Never)
            .withPortfolioGuidanceObserver { _, _ -> }.withPortfolioSessionObserver { _, _ -> }

        assertTrue(assertNotNull(invocation.linearSource).automaticCutSelection)
        assertSame(source.semantics, invocation.linearSource?.view?.model?.semantics)
        assertTrue(request.theory.sourcePointCuts)
        assertTrue(request.theory.sourceAggregateCuts)
        assertTrue(request.automaticLinearCuts.point)
        assertTrue(request.automaticLinearCuts.aggregate)
    }

    @Test
    fun `an incompatible prepared mapping should disable automatically supplied cuts`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE))
        val prepared = assertNotNull(invocation.linearSource)
        val producer = assertNotNull(prepared.toleranceAuthority)
        val nominal = prepared.view.model
        val mapping = SourceMapping(nominal, nominal, TransformationGuarantees.IDENTITY, lift = { it })
        val request = SourceExecutionRequest(invocation)

        val resolved = FiniteCompanionPolicy(request.theory, request.automaticLinearCuts).resolve(
            producer.takeIf { it.compatible(mapping, nominal, invocation.selection.finiteObjective) })

        assertFalse(resolved.sourcePointCuts)
        assertFalse(resolved.sourceAggregateCuts)
    }

    @Test
    fun `explicit point controls should survive an unavailable automatic authority`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE))
        for (enabled in listOf(false, true)) {
            val request = SourceExecutionRequest(invocation,
                ExecutionOptions(ExecutionOption.sourcePointCuts(enabled)))

            val resolved = FiniteCompanionPolicy(request.theory, request.automaticLinearCuts).resolve(null)

            assertEquals(enabled, request.theory.sourceAggregateCuts)
            assertEquals(enabled, resolved.sourcePointCuts)
            assertFalse(resolved.sourceAggregateCuts)
        }
    }

    @Test
    fun `public theory values should be explicit when supplied to another request`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE))
        val defaults = SourceExecutionRequest(invocation)
        val explicit = SourceExecutionRequest(invocation, theory = defaults.theory)

        val resolved = FiniteCompanionPolicy(explicit.theory, explicit.automaticLinearCuts).resolve(null)

        assertFalse(explicit.automaticLinearCuts.point)
        assertFalse(explicit.automaticLinearCuts.aggregate)
        assertTrue(resolved.sourcePointCuts)
        assertTrue(resolved.sourceAggregateCuts)
    }

    @Test
    fun `an explicit envelope opt out should preserve nominal preparation and cut defaults`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE,
            linearToleranceEnvelope = false))
        val request = SourceExecutionRequest(invocation)

        assertNull(invocation.linearSource?.toleranceAuthority)
        assertFalse(assertNotNull(invocation.linearSource).automaticCutSelection)
        assertFalse(request.theory.sourcePointCuts)
        assertFalse(request.theory.sourceAggregateCuts)
    }

    @Test
    fun `relative tolerance should retain nominal automatic preparation`() {
        val relative = LinearSourceProblem(source.variableValues.toList(), source.rowValues.toList(), source.objective,
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                relative = SemanticRational.ofLong(1))).resolve())

        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(relative, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE))

        assertNull(invocation.linearSource?.toleranceAuthority)
        assertFalse(assertNotNull(invocation.linearSource).acceptedModelExact)
        assertSame(relative.semantics, invocation.linearSource?.view?.model?.semantics)
    }

    @Test
    fun `an incompatible integral real layout should retain nominal automatic execution`() {
        val integral = LinearSourceProblem(source.variableValues.toList(), listOf(
            LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ONE),
                LinearSourceTerm(1, SemanticRational.ofLong(-1))), SemanticRational.ZERO, SemanticRational.ZERO)),
            source.objective, source.semantics)

        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(integral, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE))

        assertNull(invocation.linearSource?.toleranceAuthority)
        assertFalse(assertNotNull(invocation.linearSource).acceptedModelExact)
        assertFalse(assertNotNull(invocation.linearSource).automaticCutSelection)
        assertSame(integral.semantics, invocation.linearSource?.view?.model?.semantics)
    }

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
        val scaled = SourceMapping(nominal, nominal, TransformationGuarantees.IDENTITY,
            objectiveAdjustment = ObjectiveAdjustment(BigFraction.ofLong(2)))

        assertTrue(producer.compatible(identity, nominal, objective))
        assertFalse(producer.compatible(identity, nominal, objective.copy(constant = objective.constant + 1L)))
        assertFalse(producer.compatible(opaque, nominal, objective))
        assertFalse(producer.compatible(scaled, nominal, objective))
        assertNull(producer.capture(nominal, objective.copy(constant = objective.constant + 1L),
            ExecutionControl.Never))
    }

    @Test
    fun `a nominal integral real backing should retain exact envelope execution`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("r", false, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ONE),
                LinearSourceTerm(1, SemanticRational.ofLong(-1))), SemanticRational.ZERO, SemanticRational.ZERO)),
            LinearSourceObjective(listOf(LinearSourceTerm(1, SemanticRational.ONE)), sense = sense),
            this.source.semantics,
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE,
            linearToleranceEnvelope = true))
        val prepared = assertNotNull(invocation.linearSource)

        assertTrue(prepared.acceptedModelExact)
        assertNull(prepared.toleranceAuthority)
        assertTrue(Term.isReal(prepared.view.sourceRealCoordinates.single()))
    }
}
