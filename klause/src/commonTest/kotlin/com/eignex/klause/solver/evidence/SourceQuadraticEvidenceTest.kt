package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.result.QuadraticCertificate
import com.eignex.klause.solver.result.QuadraticClaim
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticObjectiveBound
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SourceQuadraticEvidenceTest {
    private val variable = QuadraticVariable.Int(SourceIntId(0))
    private val objective = QuadraticObjectiveSpec(listOf(
        QuadraticObjectiveTerm(QuadraticCoefficient.ONE, variable, variable),
    ))
    private val model = Problem(0, 1, arrayOf(IntDomain(-2, 3)), emptyArray())
    private val scope = SourceScope(SourceRevision("quadratic-original", "klause-ir-exact-v1"),
        objective = SourceObjective("square", ObjectiveSense.MINIMIZE))

    @Test
    fun `an original quadratic interval proof establishes its source bound`() {
        val context = assertNotNull(model.quadraticSourceProofContext(scope, objective))
        val evidence = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ZERO), SourceQuadraticBoxDerivation)

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `an original interval cannot establish a stronger quadratic bound`() {
        val context = assertNotNull(model.quadraticSourceProofContext(scope, objective))
        val evidence = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ONE), SourceQuadraticBoxDerivation)

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `a nonstrict quadratic interval proof cannot establish nonattainment`() {
        val context = assertNotNull(model.quadraticSourceProofContext(scope, objective))
        val evidence = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ZERO, strict = true),
            SourceQuadraticBoxDerivation)

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `an attained quadratic interval bound has checkable completed result evidence`() {
        val context = assertNotNull(model.quadraticSourceProofContext(scope, objective))
        val zero = AlgebraicArithmetic(PolynomialArithmetic()).rational(BigFraction.ZERO)
        val point = QuadraticIncumbent(Sample(booleanArrayOf(), longArrayOf(0)), zero)
        val identity = ModelIdentity.of(model, objective)
        val boundCertificate = QuadraticCertificate.attested(identity, scope, QuadraticClaim.Bound)
        val terminal = QuadraticCertificate.attested(identity, scope, QuadraticClaim.Optimal)
        val result = QuadraticOptimizationResult.Optimal(point, QuadraticObjectiveBound(zero, false, boundCertificate),
            terminal, ObjectiveSense.MINIMIZE, SolveStats.EMPTY)

        val captured = SourceResultCapture(context).quadratic(result, "fixture-engine",
            boundDerivation = SourceQuadraticBoxDerivation)

        assertEquals(SourceEvidenceCheck.Checked, captured.verifySourceEvidence(context))
    }

    @Test
    fun `an engine optimum label without a quadratic derivation remains unavailable`() {
        val context = assertNotNull(model.quadraticSourceProofContext(scope, objective))
        val zero = AlgebraicArithmetic(PolynomialArithmetic()).rational(BigFraction.ZERO)
        val point = QuadraticIncumbent(Sample(booleanArrayOf(), longArrayOf(0)), zero)
        val identity = ModelIdentity.of(model, objective)
        val boundCertificate = QuadraticCertificate.attested(identity, scope, QuadraticClaim.Bound)
        val terminal = QuadraticCertificate.attested(identity, scope, QuadraticClaim.Optimal)
        val result = QuadraticOptimizationResult.Optimal(point, QuadraticObjectiveBound(zero, false, boundCertificate),
            terminal, ObjectiveSense.MINIMIZE, SolveStats.EMPTY)

        val captured = SourceResultCapture(context).quadratic(result, "fixture-engine")

        assertIs<SourceEvidenceCheck.Unavailable>(captured.verifySourceEvidence(context))
    }

    @Test
    fun `a scoped quadratic attestation cannot be used for another invocation`() {
        val context = assertNotNull(model.quadraticSourceProofContext(scope, objective))
        val foreign = scope.copy(condition = SourceCondition(bools = mapOf(0 to true)))
        val identity = ModelIdentity.of(model, objective)
        val certificate = QuadraticCertificate.attested(identity, foreign, QuadraticClaim.Bound)

        assertIs<SourceEvidenceCheck.Rejected>(certificate.verify(SourceClaim.Bound(BigFraction.ZERO), context))
    }


    @Test
    fun `quadratic capture cannot rebind a conditional completion to a weaker source scope`() {
        val context = assertNotNull(model.quadraticSourceProofContext(scope, objective))
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val conditional = scope.copy(condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ONE)))
        val one = AlgebraicArithmetic().rational(BigFraction.ONE)
        val point = QuadraticIncumbent(Sample(booleanArrayOf(), longArrayOf(1)), one)
        val identity = ModelIdentity.of(model, objective)
        val boundCertificate = QuadraticCertificate.attested(identity, conditional, QuadraticClaim.Bound)
        val terminal = QuadraticCertificate.attested(identity, conditional, QuadraticClaim.Optimal)
        val result = QuadraticOptimizationResult.Optimal(point, QuadraticObjectiveBound(one, false, boundCertificate),
            terminal, ObjectiveSense.MINIMIZE, SolveStats.EMPTY)

        val captured = SourceResultCapture(context).quadratic(result, "fixture-engine")

        assertEquals(result, captured)
        assertIs<SourceEvidenceCheck.Rejected>(captured.verifySourceEvidence(context))
    }

}
