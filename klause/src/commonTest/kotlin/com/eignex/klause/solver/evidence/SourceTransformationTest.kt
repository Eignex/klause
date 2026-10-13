package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceTransformationTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val sourceScope = SourceScope(SourceRevision("original", "exact-affine-v1"),
        objective = SourceObjective("original-x", ObjectiveSense.MINIMIZE))
    private val targetScope = SourceScope(SourceRevision("reduced", "exact-affine-v1"),
        objective = SourceObjective("reduced-x", ObjectiveSense.MINIMIZE))
    private val rows = mapOf(
        "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
        "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO)),
    )
    private val source = SourceProofContext(sourceScope, setOf(x), rows, mapOf(x to BigFraction.ONE))
    private val target = SourceProofContext(targetScope, setOf(x), rows, mapOf(x to BigFraction.ONE))
    private val images = mapOf(x to SourceAffineImage(mapOf(x to BigFraction.ONE)))
    private val obligations = rows.keys.associateWith { SourceDerivation.Premise(it) }
    private val transformation = SourceTransformation(sourceScope, targetScope, images, obligations)
    private val refutation = SourceEvidence(targetScope, SourceClaim.Refutation, SourceDerivation.Combination(listOf(
        SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("lower")),
        SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("upper")),
    )))

    @Test
    fun `a missing target coordinate image withholds projection coverage`() {
        val mapping = SourceTransformation(sourceScope, targetScope, emptyMap(), obligations)

        val checked = SourceTransformationVerifier(source, target).verify(mapping)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `integer projections withhold nonintegral coordinate images`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val original = SourceProofContext(sourceScope, setOf(integer, x), emptyMap(), mapOf(integer to BigFraction.ONE))
        val reduced = SourceProofContext(targetScope, setOf(integer), emptyMap(), mapOf(integer to BigFraction.ONE))
        val half = BigFraction.ofLong(2).reciprocal()
        val images = listOf(
            SourceAffineImage(mapOf(integer to half)),
            SourceAffineImage(mapOf(x to BigFraction.ONE)),
            SourceAffineImage(mapOf(integer to BigFraction.ONE), constant = half),
        )

        for (image in images) {
            val mapping = SourceTransformation(sourceScope, targetScope, mapOf(integer to image), emptyMap())

            val checked = SourceTransformationVerifier(original, reduced).verify(mapping)

            assertIs<SourceEvidenceCheck.Unavailable>(checked)
        }
    }

    @Test
    fun `an integral affine image preserves target integer declarations`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val original = SourceProofContext(sourceScope, setOf(integer), emptyMap(), mapOf(integer to BigFraction.ONE))
        val reduced = SourceProofContext(targetScope, setOf(integer), emptyMap(), mapOf(integer to BigFraction.ONE))
        val image = SourceAffineImage(mapOf(integer to BigFraction.ofLong(2)), constant = BigFraction.ofLong(3))
        val mapping = SourceTransformation(sourceScope, targetScope, mapOf(integer to image), emptyMap())

        val checked = SourceTransformationVerifier(original, reduced).verify(mapping)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `objective projections reject nonpositive scales`() {
        for (scale in listOf(BigFraction.ZERO, BigFraction.MINUS_ONE)) {
            val mapping = SourceTransformation(sourceScope, targetScope, images, obligations, objectiveScale = scale)

            val checked = SourceTransformationVerifier(source, target).objective(mapping)

            assertIs<SourceEvidenceCheck.Rejected>(checked)
        }
    }

    @Test
    fun `checked projection premises transfer a reduced refutation to original source`() {
        val verdict = SourceTransformationVerifier(source, target).refutation(transformation, refutation)

        assertEquals(SourceEvidenceCheck.Checked, verdict)
    }

    @Test
    fun `a changed source mapping cannot reuse an old row justification`() {
        val changed = SourceTransformation(sourceScope, targetScope,
            mapOf(x to SourceAffineImage(mapOf(x to BigFraction.MINUS_ONE))), obligations)

        assertIs<SourceEvidenceCheck.Rejected>(SourceTransformationVerifier(source, target).verify(changed))
    }

    @Test
    fun `missing transformation obligations withhold refutation coverage`() {
        val missing = SourceTransformation(sourceScope, targetScope, images, emptyMap())

        assertIs<SourceEvidenceCheck.Unavailable>(SourceTransformationVerifier(source,
            target).refutation(missing, refutation))
    }

    @Test
    fun `a stronger target invocation needs an original source justification`() {
        val conditioned = targetScope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ofLong(2))))
        val target = SourceProofContext(conditioned, setOf(x), rows, mapOf(x to BigFraction.ONE))
        val changed = SourceTransformation(sourceScope, conditioned, images, obligations)

        assertIs<SourceEvidenceCheck.Unavailable>(SourceTransformationVerifier(source, target).verify(changed))
    }

    @Test
    fun `a wrong objective offset cannot establish source objective preservation`() {
        val source = SourceProofContext(sourceScope, setOf(x), mapOf("lower" to rows.getValue("lower")),
            mapOf(x to BigFraction.ONE))
        val target = SourceProofContext(targetScope, setOf(x), mapOf("lower" to rows.getValue("lower")),
            mapOf(x to BigFraction.ONE))
        val changed = SourceTransformation(sourceScope, targetScope, images, mapOf(
            "lower" to SourceDerivation.Premise("lower"),
            "objective/upper" to SourceDerivation.Combination(emptyList()),
            "objective/lower" to SourceDerivation.Combination(emptyList()),
        ), objectiveOffset = BigFraction.ONE)

        assertIs<SourceEvidenceCheck.Rejected>(SourceTransformationVerifier(source, target).objective(changed))
    }

    @Test
    fun `an exact identity projection preserves the original objective`() {
        assertEquals(SourceEvidenceCheck.Checked, SourceTransformationVerifier(source,
            target).objective(transformation))
    }

    @Test
    fun `transformation and terminal proof share one containing allowance`() {
        val verifier = SourceTransformationVerifier(source, target, maximumSteps = 20)

        assertEquals(SourceEvidenceCheck.Checked, verifier.verify(transformation))
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(target, maximumSteps = 20).verify(refutation))
        assertIs<SourceEvidenceCheck.Unavailable>(verifier.refutation(transformation, refutation))
    }

    @Test
    fun `uncovered target semantics cannot acquire source authority from a projection`() {
        val semanticProof = object : SourceDerivation.Extension {}
        val checkedTarget = object : SourceSemanticsChecker {
            override fun witness(sample: com.eignex.klause.solver.Sample, scope: SourceScope,
                cancellation: com.eignex.klause.util.Cancellation): SourceEvidenceCheck = SourceEvidenceCheck.Checked
            override fun ray(claim: SourceClaim.Ray, scope: SourceScope,
                cancellation: com.eignex.klause.util.Cancellation): SourceEvidenceCheck = SourceEvidenceCheck.Checked
            override fun derivation(claim: SourceClaim, derivation: SourceDerivation.Extension, scope: SourceScope,
                cancellation: com.eignex.klause.util.Cancellation): SourceEvidenceCheck = SourceEvidenceCheck.Checked
        }
        val target = SourceProofContext(targetScope, setOf(x), rows, mapOf(x to BigFraction.ONE),
            semanticsChecker = checkedTarget)
        val evidence = SourceEvidence(targetScope, SourceClaim.Refutation, semanticProof)

        assertIs<SourceEvidenceCheck.Unavailable>(SourceTransformationVerifier(source,
            target).refutation(transformation, evidence))
    }

    @Test
    fun `a transformation cannot accept an undeclared original invocation coordinate`() {
        val scope = sourceScope.copy(condition = SourceCondition(bools = mapOf(0 to true)))
        val original = SourceProofContext(scope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val reduced = SourceProofContext(targetScope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val mapping = SourceTransformation(scope, targetScope,
            mapOf(x to SourceAffineImage(mapOf(x to BigFraction.ONE))), emptyMap())

        assertIs<SourceEvidenceCheck.Rejected>(SourceTransformationVerifier(original, reduced).verify(mapping))
    }

}
