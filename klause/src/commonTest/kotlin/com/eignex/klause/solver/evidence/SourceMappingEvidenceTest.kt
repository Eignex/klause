package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceMappingEvidenceTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val source = SourceScope(SourceRevision("source", "affine-exact"))
    private val target = SourceScope(SourceRevision("target", "affine-exact"))
    private val context = SourceProofContext(source, setOf(x), mapOf(
        "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
        "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO))))
    private val proof = SourceDerivation.Combination(listOf(
        SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("lower")),
        SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("upper"))))
    private val transformation = SourceTransformation(source, target,
        mapOf(x to SourceAffineImage(mapOf(x to BigFraction.ONE))), mapOf(
            "lower" to SourceDerivation.Premise("lower"), "upper" to SourceDerivation.Premise("upper")))

    @Test
    fun `mapped imported refutations are checked against original premises`() {
        val imported = SourceEvidence(target, SourceClaim.Refutation, proof)
        val payload = SourceResultEvidence(target, listOf(SourceEvidence(target, SourceClaim.Refutation,
            SourceDerivation.Import(imported))))
        val mapping = SourceMappingEvidence(source, payload, listOf(transformation))

        val checked = mapping.verify(SourceClaim.Refutation, context)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `mapping cannot import a refutation from a stronger invocation`() {
        val pinned = target.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ONE)))
        val imported = SourceEvidence(pinned, SourceClaim.Refutation, proof)
        val payload = SourceResultEvidence(target, listOf(SourceEvidence(target, SourceClaim.Refutation,
            SourceDerivation.Import(imported))))
        val mapping = SourceMappingEvidence(source, payload, listOf(transformation))

        val checked = mapping.verify(SourceClaim.Refutation, context)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `mapping does not grant authority to a supplied false premise justification`() {
        val consistent = SourceProofContext(source, setOf(x), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE))))
        val payload = SourceResultEvidence(target, listOf(SourceEvidence(target, SourceClaim.Refutation, proof)))
        val mapping = SourceMappingEvidence(source, payload, listOf(transformation))

        val checked = mapping.verify(SourceClaim.Refutation, consistent)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `cancelled mapping checks withhold original coverage`() {
        val payload = SourceResultEvidence(target, listOf(SourceEvidence(target, SourceClaim.Refutation, proof)))
        val mapping = SourceMappingEvidence(source, payload, listOf(transformation))

        val checked = mapping.verify(SourceClaim.Refutation, context, Cancellation { true })

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }
}
