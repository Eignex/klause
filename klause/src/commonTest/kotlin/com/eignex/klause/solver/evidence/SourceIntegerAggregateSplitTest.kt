package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SourceIntegerAggregateSplitTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
    private val scope = SourceScope(SourceRevision("aggregate", "exact-affine"))
    private val form = mapOf(x to BigFraction.ONE, y to BigFraction.ONE)
    private val below = SourceInequality(form, BigFraction.ZERO)
    private val above = below.adjacentIntegerAbove()
    private val quarter = BigFraction.ofLong(4).reciprocal()
    private val source = SourceProofContext(scope, setOf(x, y), mapOf(
        "lower" to SourcePremise(SourceInequality(form.mapValues { it.value.negated() }, quarter.negated())),
        "upper" to SourcePremise(SourceInequality(form, BigFraction.ONE - quarter)),
    ))

    private fun child(id: String, row: SourceInequality): SourceDerivation =
        SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise(id)),
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceIntegerRowAssumption(row)),
        ))

    private fun proof(): SourceDerivation =
        SourceIntegerAggregateSplit(below, child("lower", below), child("upper", above))

    @Test
    fun `adjacent aggregate alternatives should cover declared integers`() {
        for (split in listOf(proof(),
            SourceIntegerAggregateSplit(above, child("upper", above), child("lower", below)))) {
            val result = SourceEvidenceVerifier(source).verify(SourceEvidence(scope, SourceClaim.Refutation, split))

            assertEquals(SourceEvidenceCheck.Checked, result)
        }
    }

    @Test
    fun `aggregate coverage should require both complementary children`() {
        val proof = SourceIntegerAggregateSplit(below, child("lower", below), child("lower", below))

        val result = SourceEvidenceVerifier(source).verify(SourceEvidence(scope, SourceClaim.Refutation, proof))

        assertIs<SourceEvidenceCheck.Rejected>(result)
    }

    @Test
    fun `aggregate coverage should reject fractional or foreign activity`() {
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        for (row in listOf(
            SourceInequality(mapOf(x to quarter, y to BigFraction.ONE), BigFraction.ZERO),
            SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ZERO),
            SourceInequality(mapOf(SourceCoordinate(SourceCoordinate.Kind.Integer, 2) to BigFraction.ONE),
                BigFraction.ZERO),
            SourceInequality(form, quarter),
            SourceInequality(form, BigFraction.ZERO, strict = true),
        )) {
            val proof = SourceIntegerAggregateSplit(row, child("lower", below), child("upper", above))

            assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(source)
                .verify(SourceEvidence(scope, SourceClaim.Refutation, proof)))
        }
    }

    @Test
    fun `aggregate assumptions should not escape their branch scope`() {
        val branch = scope.copy(condition = scope.condition.integerBound(below))
        val evidence = SourceEvidence(branch, SourceClaim.Refutation, child("lower", below))
        val imported = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Import(evidence))

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(source).verify(imported))
    }

    @Test
    fun `Boolean alternatives should supply no aggregate assumption`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val context = SourceProofContext(scope, source.coordinates + bool, source.premises)
        val proof = SourceDerivation.BooleanSplit(0, child("lower", below), child("upper", above))

        val result = SourceEvidenceVerifier(context).verify(SourceEvidence(scope, SourceClaim.Refutation, proof))

        assertIs<SourceEvidenceCheck.Rejected>(result)
    }

    @Test
    fun `aggregate siblings should share the containing proof allowance`() {
        for ((condition, proof) in listOf(below to child("lower", below), above to child("upper", above))) {
            val branch = scope.copy(condition = scope.condition.integerBound(condition))
            val context = SourceProofContext(branch, source.coordinates, source.premises)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context, maximumSteps = 16)
                .verify(SourceEvidence(branch, SourceClaim.Refutation, proof)))
        }
        val result = SourceEvidenceVerifier(source, maximumSteps = 16)
            .verify(SourceEvidence(scope, SourceClaim.Refutation, proof()))

        assertIs<SourceEvidenceCheck.Unavailable>(result)
    }

    @Test
    fun `cancelled aggregate coverage should supply no refutation`() {
        val result = SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Refutation, proof()), Cancellation { true })

        assertIs<SourceEvidenceCheck.Unavailable>(result)
    }

    @Test
    fun `replaying aggregate coverage should preserve its immutable parent condition`() {
        val condition = scope.condition.integerBound(below)
        val sibling = scope.condition.integerBound(above)
        val pinned = condition.pin(0, true).bound(x, BigFraction.ZERO, true)
        val evidence = SourceEvidence(scope, SourceClaim.Refutation, proof())

        repeat(2) {
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(evidence))
        }
        assertFalse(condition.implies(sibling))
        assertFalse(scope.condition.implies(condition))
        assertEquals(setOf(below), pinned.integerRows)
        assertEquals(emptySet(), scope.condition.integerRows)
    }

    @Test
    fun `a custom witness checker should not bypass aggregate restrictions`() {
        val branch = scope.copy(condition = scope.condition.integerBound(below))
        val checker = object : SourceSemanticsChecker {
            override fun witness(sample: Sample, scope: SourceScope, cancellation: Cancellation) =
                SourceEvidenceCheck.Checked
            override fun ray(claim: SourceClaim.Ray, scope: SourceScope, cancellation: Cancellation) =
                SourceEvidenceCheck.Checked
        }
        val context = SourceProofContext(branch, setOf(x, y), emptyMap(), semanticsChecker = checker)
        val sample = Sample(booleanArrayOf(), longArrayOf(1, 0))

        val result = SourceEvidenceVerifier(context).verify(
            SourceEvidence(branch, SourceClaim.Witness(sample), SourceDerivation.SourceWitness))

        assertIs<SourceEvidenceCheck.Rejected>(result)
    }
}
