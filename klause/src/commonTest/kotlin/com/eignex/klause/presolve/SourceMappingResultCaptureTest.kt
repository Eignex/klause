package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceAffineImage
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceResultCapture
import com.eignex.klause.solver.evidence.SourceResultEvidence
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceTransformation
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.evidence.sourceResultCapture
import com.eignex.klause.solver.incumbent.CompletedEngineResult
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SourceMappingResultCaptureTest {
    private val revision = SourceRevision("original", "klause-ir-exact-v1")
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)

    @Test
    fun `a reconstructed witness retains its original check and reduced payload`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 5) },
            arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1)))
        val target = source.withFactors(emptyArray())
        val capture = assertNotNull(source.prepareExecutionPlan().sourceResultCapture(revision))
        val targetScope = SourceScope(revision.copy(identity = "reduced"))
        val targetContext = assertNotNull(target.sourceProofContext(targetScope))
        val reduced = SourceResultCapture(targetContext).solve(
            SolveResult.Sat(Sample(booleanArrayOf(), longArrayOf(0, 2))), "fixture-engine")
        val rebuild = SourceRebuilds(listOf(RebuildStep.AffineValue(0, 1, intArrayOf(1), longArrayOf(1), 1)))
        val mapping = SourceMapping(source, target, PresolvePass.ELIMINATE_AFFINE_SINGLETONS.guarantees, rebuild)

        val result = assertIs<SolveResult.Sat>(mapping.captureSolve(target, reduced, capture, "fixture-mapping"))

        assertEquals(listOf(3L, 2L), result.assignment.ints.toList())
        val evidence = assertNotNull(result.evidence)
        assertEquals(SourceEvidenceCheck.Checked,
            evidence.verify(SourceClaim.Witness(result.assignment), capture.context))
        assertSame(reduced.evidence, evidence.mappings.single().target)
        assertEquals(listOf(null), evidence.mappings.single().transformations)
    }

    @Test
    fun `a reconstructed source witness excludes private target coordinates`() {
        val source = Problem(1, 0, emptyArray<IntDomain>(), emptyArray())
        val target = Problem(2, 0, emptyArray<IntDomain>(), emptyArray())
        val capture = assertNotNull(source.prepareExecutionPlan().sourceResultCapture(revision))
        val mapping = SourceMapping(source, target, TransformationGuarantees.IDENTITY)
        val reduced = SolveResult.Sat(Sample(booleanArrayOf(false, true), longArrayOf()))

        val result = assertIs<SolveResult.Sat>(mapping.captureSolve(target, reduced, capture, "fixture-mapping"))

        assertEquals(1, result.assignment.numBoolVars)
        assertEquals(SourceEvidenceCheck.Checked, assertNotNull(result.evidence)
            .verify(SourceClaim.Witness(result.assignment), capture.context))
    }

    @Test
    fun `an unjustified conditional refutation cannot acquire original engine admission`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val target = source.withFactors(emptyArray())
        val capture = assertNotNull(source.prepareExecutionPlan().sourceResultCapture(revision))
        val targetScope = SourceScope(revision.copy(identity = "reduced"), SourceCondition(
            lower = mapOf(x to BigFraction.ofLong(2)), upper = mapOf(x to BigFraction.ofLong(2))))
        val targetContext = assertNotNull(target.sourceProofContext(targetScope))
        val proof = SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("int/0/upper")),
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, false))))
        val reduced = SourceResultCapture(targetContext).solve(SolveResult.Unsat(), "fixture-engine", proof)
        val mapping = SourceMapping(source, target, TransformationGuarantees.IDENTITY)

        val result = mapping.captureSolve(target, reduced, capture, "fixture-mapping")

        assertEquals(targetScope, assertNotNull(result.evidence).engineScope)
        assertIs<SourceEvidenceCheck.Unavailable>(assertNotNull(result.evidence)
            .verify(SourceClaim.Refutation, capture.context))
        assertNull(EvidenceCertificate.completed(ModelIdentity.of(source), EvidenceKind.Infeasible,
            CompletedEngineResult.Solve(capture.context.scope, result)))
    }

    @Test
    fun `identity recapture preserves an explicitly withheld engine scope`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val capture = assertNotNull(source.prepareExecutionPlan().sourceResultCapture(revision))
        val evidence = SourceResultEvidence(capture.context.scope, listOf(SourceEvidence(capture.context.scope,
            SourceClaim.Refutation, SourceDerivation.Unavailable("missing projection"))))
            .withMappings(null, emptyList())
        val mapping = SourceMapping(source, source, TransformationGuarantees.IDENTITY)

        val result = mapping.captureSolve(source, SolveResult.Unsat(evidence = evidence), capture, "fixture-mapping")

        assertNull(assertNotNull(result.evidence).engineScope)
        assertNull(EvidenceCertificate.completed(ModelIdentity.of(source), EvidenceKind.Infeasible,
            CompletedEngineResult.Solve(capture.context.scope, result)))
    }

    @Test
    fun `a mapped refutation uses justified original premises for terminal admission`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)))
        val target = source.withFactors(emptyArray())
        val capture = assertNotNull(source.prepareExecutionPlan().sourceResultCapture(revision))
        val targetScope = SourceScope(revision.copy(identity = "reduced"), SourceCondition(
            lower = mapOf(x to BigFraction.ofLong(2)), upper = mapOf(x to BigFraction.ofLong(2))))
        val targetContext = assertNotNull(target.sourceProofContext(targetScope))
        val proof = SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("int/0/upper")),
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, false))))
        val reduced = SourceResultCapture(targetContext).solve(SolveResult.Unsat(), "fixture-engine", proof)
        val transformation = SourceTransformation(capture.context.scope, targetScope,
            mapOf(x to SourceAffineImage(mapOf(x to BigFraction.ONE))), mapOf(
                "int/0/upper" to SourceDerivation.Premise("int/0/upper"),
                "assumption/Integer/0/lower" to SourceDerivation.Premise("factor/0/row/0/booleans/0/side/0")))
        val mapping = SourceMapping(source, target, TransformationGuarantees.IDENTITY, justification = transformation)

        val result = mapping.captureSolve(target, reduced, capture, "fixture-mapping")

        val evidence = assertNotNull(result.evidence)
        assertEquals(SourceEvidenceCheck.Checked, evidence.verify(SourceClaim.Refutation, capture.context))
        assertEquals(capture.context.scope, evidence.engineScope)
        assertNotNull(EvidenceCertificate.completed(ModelIdentity.of(source), EvidenceKind.Infeasible,
            CompletedEngineResult.Solve(capture.context.scope, result)))
    }

    @Test
    fun `mapped objective bounds retain original direction scale and offset`() {
        for ((sense, point, reducedPoint) in listOf(
            Triple(ObjectiveSense.MINIMIZE, 3L, 0L), Triple(ObjectiveSense.MAXIMIZE, 9L, 6L))) {
            val source = Problem(0, 1, arrayOf(IntDomain(3, 9)), emptyArray())
            val target = Problem(0, 1, arrayOf(IntDomain(0, 6)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(2), constant = 1)
            val targetObjective = LinearObjective(intCoefficients = longArrayOf(1))
            val plan = source.prepareExecutionPlan(objective, maximize = sense == ObjectiveSense.MAXIMIZE)
            val capture = assertNotNull(plan.sourceResultCapture(revision, objectiveIdentity = "x"))
            val targetScope = SourceScope(revision.copy(identity = "reduced"), objective = SourceObjective("y", sense))
            val targetContext = assertNotNull(target.sourceProofContext(targetScope, targetObjective))
            val side = if (sense == ObjectiveSense.MINIMIZE) "lower" else "upper"
            val proof = SourceDerivation.Optimum(
                SourceDerivation.SourceWitness, SourceDerivation.Premise("int/0/$side"))
            val cost = if (sense == ObjectiveSense.MINIMIZE) reducedPoint.toDouble() else -reducedPoint.toDouble()
            val reduced = SourceResultCapture(targetContext).minimize(
                MinimizeResult.Optimal(Sample(booleanArrayOf(), longArrayOf(reducedPoint)), cost),
                targetObjective, "fixture-engine", proof)
            val transformation = SourceTransformation(capture.context.scope, targetScope,
                mapOf(x to SourceAffineImage(mapOf(x to BigFraction.ONE), BigFraction.ofLong(-3))),
                mapOf("int/0/$side" to SourceDerivation.Premise("int/0/$side")),
                BigFraction.ofLong(2), BigFraction.ofLong(7))
            val mapping = SourceMapping(source, target, TransformationGuarantees.IDENTITY,
                SourceRebuilds(listOf(RebuildStep.AffineValue(0, 3, intArrayOf(0), longArrayOf(1), 1))),
                ObjectiveAdjustment(BigFraction.ofLong(2), BigFraction.ofLong(7)), justification = transformation)

            val result = assertIs<MinimizeResult.Optimal>(mapping.captureMinimize(
                target, reduced, capture, objective, "fixture-mapping"))

            val value = 2 * point + 1
            assertEquals(point, result.sample.intValue(0))
            assertEquals(if (sense == ObjectiveSense.MINIMIZE) value.toDouble() else -value.toDouble(),
                result.objective)
            val claim = SourceClaim.Optimal(result.sample, SourceValue.Rational(BigFraction.ofLong(value)))
            assertEquals(SourceEvidenceCheck.Checked, assertNotNull(result.evidence).verify(claim, capture.context))
            assertEquals(capture.context.scope, assertNotNull(result.evidence).engineScope)
        }
    }
}
