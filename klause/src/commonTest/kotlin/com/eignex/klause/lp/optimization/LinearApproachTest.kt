package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LinearApproachTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("strict-real", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
    private val source = SourceProofContext(scope, setOf(real), mapOf("positive" to SourcePremise(
        SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true))),
        mapOf(real to BigFraction.ONE)).withLinearAuthority()

    @Test
    fun `a strict global bound and feasible segment certify an unattained infimum`() {
        val base = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        val endpoint = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))
        val evidence = SourceEvidence(scope, SourceClaim.Limit(base, SourceValue.Rational(BigFraction.ZERO)),
            LinearApproach(endpoint, SourceDerivation.Premise("positive")))

        val checked = SourceEvidenceVerifier(source).verify(evidence)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `a closure endpoint is not an attained source optimum`() {
        val endpoint = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))
        val evidence = SourceEvidence(scope, SourceClaim.Optimal(endpoint, SourceValue.Rational(BigFraction.ZERO)),
            SourceDerivation.Optimum(SourceDerivation.SourceWitness, SourceDerivation.Premise("positive")))

        val checked = SourceEvidenceVerifier(source).verify(evidence)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `an integer approach cannot interpolate between distinct integer points`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val integerSource = SourceProofContext(scope, setOf(integer), mapOf("positive" to SourcePremise(
            SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true))),
            mapOf(integer to BigFraction.ONE)).withLinearAuthority()
        val evidence = SourceEvidence(scope, SourceClaim.Limit(Sample(booleanArrayOf(), longArrayOf(1)),
            SourceValue.Rational(BigFraction.ZERO)), LinearApproach(Sample(booleanArrayOf(), longArrayOf(0)),
            SourceDerivation.Premise("positive")))

        val checked = SourceEvidenceVerifier(integerSource).verify(evidence)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `an approach cannot leave the invocation restrictions`() {
        val restrictedScope = scope.copy(condition = SourceCondition(lower = mapOf(real to BigFraction.ONE)))
        val restricted = SourceProofContext(restrictedScope, source.coordinates, source.premises,
            source.objective).withLinearAuthority()
        val base = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        val endpoint = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))
        val evidence = SourceEvidence(restrictedScope, SourceClaim.Limit(base, SourceValue.Rational(BigFraction.ZERO)),
            LinearApproach(endpoint, SourceDerivation.Premise("positive")))

        val checked = SourceEvidenceVerifier(restricted).verify(evidence)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `cancelled approach checking withholds the limit`() {
        val base = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        val endpoint = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))
        val evidence = SourceEvidence(scope, SourceClaim.Limit(base, SourceValue.Rational(BigFraction.ZERO)),
            LinearApproach(endpoint, SourceDerivation.Premise("positive")))

        val checked = SourceEvidenceVerifier(source).verify(evidence, Cancellation { true })

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }
}
