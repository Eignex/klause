package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
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
    @Test
    fun `unavailable mapped refutation does not mask a later imported proof`() {
        val payload = SourceResultEvidence(target, listOf(
            SourceEvidence(target, SourceClaim.Refutation, SourceDerivation.Unavailable("local capture unavailable")),
            SourceEvidence(target, SourceClaim.Refutation,
                SourceDerivation.Import(SourceEvidence(target, SourceClaim.Refutation, proof)))))
        val mapping = SourceMappingEvidence(source, payload, listOf(transformation))

        val checked = mapping.verify(SourceClaim.Refutation, context)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `invalid mapped proof is rejected before a later valid alternative`() {
        val payload = SourceResultEvidence(target, listOf(
            SourceEvidence(target, SourceClaim.Refutation, SourceDerivation.Premise("upper")),
            SourceEvidence(target, SourceClaim.Refutation, proof)))
        val mapping = SourceMappingEvidence(source, payload, listOf(transformation))

        val checked = mapping.verify(SourceClaim.Refutation, context)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `mapped bound selection uses original objective scale and offset`() {
        val sourceGoal = source.copy(objective = SourceObjective("source objective", ObjectiveSense.MINIMIZE))
        val targetGoal = target.copy(objective = SourceObjective("target objective", ObjectiveSense.MINIMIZE))
        val lower = SourceDerivation.Premise("lower")
        val mapped = SourceTransformation(sourceGoal, targetGoal,
            mapOf(x to SourceAffineImage(mapOf(x to BigFraction.ONE))), mapOf("lower" to lower),
            objectiveScale = BigFraction.ofLong(2), objectiveOffset = BigFraction.ofLong(7))
        val original = SourceProofContext(sourceGoal, setOf(x), mapOf("lower" to
            SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))),
            objective = mapOf(x to BigFraction.ofLong(2)), objectiveConstant = BigFraction.ofLong(7))
        val requested = SourceClaim.Bound(BigFraction.ofLong(9))
        val payload = SourceResultEvidence(targetGoal, listOf(
            SourceEvidence(targetGoal, SourceClaim.Bound(BigFraction.ZERO), lower),
            SourceEvidence(targetGoal, SourceClaim.Bound(BigFraction.ONE),
                SourceDerivation.Unavailable("local capture unavailable")),
            SourceEvidence(targetGoal, SourceClaim.Bound(BigFraction.ONE),
                SourceDerivation.Import(SourceEvidence(targetGoal, SourceClaim.Bound(BigFraction.ONE), lower)))))
        val mapping = SourceMappingEvidence(sourceGoal, payload, listOf(mapped))

        val checked = mapping.verify(requested, original)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `mapped alternatives cannot renew an exhausted allowance`() {
        val payload = SourceResultEvidence(target, listOf(
            SourceEvidence(target, SourceClaim.Refutation, SourceDerivation.Unavailable("local capture unavailable")),
            SourceEvidence(target, SourceClaim.Refutation, proof)))
        val mapping = SourceMappingEvidence(source, payload, listOf(transformation))

        val projected = mapping.project(
            SourceClaim.Refutation, context, Cancellation.Never, SourceProofAllowance(3, 128))

        assertIs<SourceDerivation.Unavailable>(projected.derivation)
    }

    @Test
    fun `mapped alternatives retain observed cancellation`() {
        val payload = SourceResultEvidence(target, listOf(
            SourceEvidence(target, SourceClaim.Refutation, SourceDerivation.Unavailable("local capture unavailable")),
            SourceEvidence(target, SourceClaim.Refutation, proof)))
        val mapping = SourceMappingEvidence(source, payload, listOf(transformation))
        var polls = 0

        val checked = mapping.verify(SourceClaim.Refutation, context, Cancellation { ++polls == 3 })

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `mapped imported optimum retains its actual bound derivation`() {
        val sourceGoal = source.copy(objective = SourceObjective("objective", ObjectiveSense.MINIMIZE))
        val targetGoal = target.copy(objective = sourceGoal.objective)
        val lower = SourceDerivation.Premise("lower")
        val mapped = SourceTransformation(sourceGoal, targetGoal,
            mapOf(x to SourceAffineImage(mapOf(x to BigFraction.ONE))), mapOf("lower" to lower))
        val original = SourceProofContext(sourceGoal, setOf(x), mapOf("lower" to
            SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))),
            objective = mapOf(x to BigFraction.ONE))
        val optimum = SourceClaim.Optimal(
            Sample(booleanArrayOf(), longArrayOf(1)), SourceValue.Rational(BigFraction.ONE))
        val imported = SourceEvidence(targetGoal, optimum,
            SourceDerivation.Optimum(SourceDerivation.SourceWitness, lower))
        val payload = SourceResultEvidence(targetGoal, listOf(SourceEvidence(targetGoal, optimum,
            SourceDerivation.Import(imported))))
        val mapping = SourceMappingEvidence(sourceGoal, payload, listOf(mapped))

        val checked = mapping.verify(SourceClaim.Bound(BigFraction.ONE), original)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

}
