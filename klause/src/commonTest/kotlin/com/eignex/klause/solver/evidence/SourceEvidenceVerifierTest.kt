package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceEvidenceVerifierTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val revision = SourceRevision("original-source-sha256", "exact-affine-v1")
    private val scope = SourceScope(revision, objective = SourceObjective("source-objective-sha256", ObjectiveSense.MINIMIZE))
    private val lower = SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)
    private val premises = mapOf("lower" to SourcePremise(lower))
    private val context = SourceProofContext(scope, setOf(x), premises, mapOf(x to BigFraction.ONE))

    @Test
    fun `a cited original row proves a bound in original objective units`() {
        val evidence = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ONE), SourceDerivation.Premise("lower"))

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `stale foreign and changed invocation scopes are rejected`() {
        val scopes = listOf(
            scope.copy(revision = revision.copy(identity = "stale")),
            scope.copy(revision = revision.copy(semantics = "tolerance")),
            scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ONE))),
            scope.copy(objective = checkNotNull(scope.objective).copy(identity = "foreign")),
            scope.copy(objective = checkNotNull(scope.objective).copy(sense = ObjectiveSense.MAXIMIZE)),
        )

        for (other in scopes) {
            val evidence = SourceEvidence(other, SourceClaim.Bound(BigFraction.ONE), SourceDerivation.Premise("lower"))
            assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(evidence))
        }
    }

    @Test
    fun `an unrelated source row cannot establish a bound`() {
        val evidence = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ofLong(2)), SourceDerivation.Premise("lower"))

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `an engine result without a derivation withholds proof coverage`() {
        val evidence = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Unavailable("CAD coverage not captured"))

        assertIs<SourceEvidenceCheck.Unavailable>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `negative multipliers cannot reverse an inequality`() {
        val proof = SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.MINUS_ONE, SourceDerivation.Premise("lower")),
        ))
        val evidence = SourceEvidence(scope, SourceClaim.Refutation, proof)

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `an exact Farkas combination refutes original premises`() {
        val upper = SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO)
        val context = SourceProofContext(scope, setOf(x), premises + ("upper" to SourcePremise(upper)))
        val proof = SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("lower")),
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("upper")),
        ))

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Refutation, proof),
        ))
    }

    @Test
    fun `zero weight does not preserve strict contradiction`() {
        val context = SourceProofContext(scope, emptySet(), mapOf(
            "strict" to SourcePremise(SourceInequality(emptyMap(), BigFraction.ZERO, strict = true)),
        ))
        val proof = SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ZERO, SourceDerivation.Premise("strict")),
        ))

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Refutation, proof),
        ))
    }

    @Test
    fun `Boolean coverage checks both guarded source branches`() {
        val b = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val impossible = SourceInequality(emptyMap(), BigFraction.MINUS_ONE)
        val context = SourceProofContext(scope, setOf(b), mapOf(
            "false" to SourcePremise(impossible, mapOf(0 to false)),
            "true" to SourcePremise(impossible, mapOf(0 to true)),
        ))
        val proof = SourceDerivation.BooleanSplit(0, SourceDerivation.Premise("false"), SourceDerivation.Premise("true"))

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Refutation, proof),
        ))
        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Refutation, proof.copy(whenTrue = SourceDerivation.Premise("false"))),
        ))
    }

    @Test
    fun `an unfinished branch withholds whole source refutation`() {
        val b = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val context = SourceProofContext(scope, setOf(b), mapOf(
            "false" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE), mapOf(0 to false)),
        ))
        val proof = SourceDerivation.BooleanSplit(
            0, SourceDerivation.Premise("false"), SourceDerivation.Unavailable("unfinished branch"),
        )

        assertIs<SourceEvidenceCheck.Unavailable>(SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Refutation, proof),
        ))
    }

    @Test
    fun `integer coverage includes adjacent integral alternatives`() {
        val i = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val half = BigFraction.of(bigIntOf(1), bigIntOf(2))
        val context = SourceProofContext(scope, setOf(i), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(i to BigFraction.MINUS_ONE), half.negated())),
            "upper" to SourcePremise(SourceInequality(mapOf(i to BigFraction.ONE), half)),
        ))
        val below = SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("lower")),
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(i, upper = true)),
        ))
        val above = SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("upper")),
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(i, upper = false)),
        ))
        val proof = SourceDerivation.IntegerSplit(i, BigFraction.ZERO, below, above)

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Refutation, proof),
        ))
        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Refutation, proof.copy(threshold = half)),
        ))
    }

    @Test
    fun `imported bounds are rechecked against original source premises`() {
        val claim = SourceClaim.Bound(BigFraction.ofLong(2))
        val imported = SourceEvidence(scope, claim, SourceDerivation.Premise("lower"))
        val evidence = SourceEvidence(scope, claim, SourceDerivation.Import(imported))

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `a neighborhood bound cannot be imported into a weaker invocation`() {
        val neighborhood = scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ofLong(2))))
        val claim = SourceClaim.Bound(BigFraction.ofLong(2))
        val imported = SourceEvidence(neighborhood, claim, SourceDerivation.Assumption(x, upper = false))

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, claim, SourceDerivation.Import(imported)),
        ))
    }

    @Test
    fun `a justified unconditional bound can enter a stronger invocation`() {
        val conditioned = scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ofLong(2))))
        val context = SourceProofContext(conditioned, setOf(x), premises, mapOf(x to BigFraction.ONE))
        val claim = SourceClaim.Bound(BigFraction.ONE)
        val imported = SourceEvidence(scope, claim, SourceDerivation.Premise("lower"))

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(
            SourceEvidence(conditioned, claim, SourceDerivation.Import(imported)),
        ))
    }

    @Test
    fun `an unsupported source checker cannot certify a point or ray`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        val claims = listOf(
            SourceClaim.Witness(sample) to SourceDerivation.SourceWitness,
            SourceClaim.Ray(sample, mapOf(x to BigFraction.MINUS_ONE)) to SourceDerivation.SourceRay,
        )

        for ((claim, proof) in claims) assertIs<SourceEvidenceCheck.Unavailable>(SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, claim, proof),
        ))
    }

    @Test
    fun `cancelled verification cannot publish checked proof coverage`() {
        val evidence = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ONE), SourceDerivation.Premise("lower"))

        assertIs<SourceEvidenceCheck.Unavailable>(SourceEvidenceVerifier(context).verify(evidence, Cancellation { true }))
    }

    @Test
    fun `proof traversal cannot reset its containing allowance through imports`() {
        val claim = SourceClaim.Bound(BigFraction.ONE)
        var evidence = SourceEvidence(scope, claim, SourceDerivation.Premise("lower"))
        repeat(5) { evidence = SourceEvidence(scope, claim, SourceDerivation.Import(evidence)) }

        assertIs<SourceEvidenceCheck.Unavailable>(SourceEvidenceVerifier(context, maximumSteps = 5).verify(evidence))
    }

    @Test
    fun `optimality requires the witness objective to equal the checked bound`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(2.0), exactReals = listOf(BigFraction.ofLong(2)))
        val checker = LinearSourceSemanticsChecker(setOf(x), premises.values.toList(), mapOf(x to BigFraction.ONE))
        val context = SourceProofContext(scope, setOf(x), premises, mapOf(x to BigFraction.ONE), semanticsChecker = checker)
        val claim = SourceClaim.Optimal(sample, SourceValue.Rational(BigFraction.ONE))
        val proof = SourceDerivation.Optimum(SourceDerivation.SourceWitness, SourceDerivation.Premise("lower"))

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(SourceEvidence(scope, claim, proof)))
    }

    @Test
    fun `an attained original source bound certifies optimality`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        val checker = LinearSourceSemanticsChecker(setOf(x), premises.values.toList(), mapOf(x to BigFraction.ONE))
        val context = SourceProofContext(scope, setOf(x), premises, mapOf(x to BigFraction.ONE), semanticsChecker = checker)
        val claim = SourceClaim.Optimal(sample, SourceValue.Rational(BigFraction.ONE))
        val proof = SourceDerivation.Optimum(SourceDerivation.SourceWitness, SourceDerivation.Premise("lower"))

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(SourceEvidence(scope, claim, proof)))
    }

    @Test
    fun `an unattained limit requires approach and nonattainment derivations`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(2.0), exactReals = listOf(BigFraction.ofLong(2)))
        val claim = SourceClaim.Limit(sample, SourceValue.Rational(BigFraction.ONE))

        assertIs<SourceEvidenceCheck.Unavailable>(SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, claim, SourceDerivation.Premise("lower")),
        ))
    }

    @Test
    fun `a malformed Boolean coverage coordinate is rejected`() {
        val proof = SourceDerivation.BooleanSplit(-1, SourceDerivation.Premise("lower"), SourceDerivation.Premise("lower"))

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Refutation, proof),
        ))
    }
}
