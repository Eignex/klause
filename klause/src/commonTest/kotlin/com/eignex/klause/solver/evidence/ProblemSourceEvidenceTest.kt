package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Bits
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProblemSourceEvidenceTest {
    private val scope = SourceScope(SourceRevision("original-ir", "klause-ir-exact-v1"))
    private val model = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf(
        Linear(longArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
    ))

    @Test
    fun `source witness checking reads the original factor constraints`() {
        val context = assertNotNull(model.sourceProofContext(scope))
        val evidence = SourceEvidence(scope, SourceClaim.Witness(Sample(booleanArrayOf(), longArrayOf(1))),
            SourceDerivation.SourceWitness)

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `a complete original source witness has checkable captured coordinates`() {
        val context = assertNotNull(model.sourceProofContext(scope))
        val evidence = SourceEvidence(scope, SourceClaim.Witness(Sample(booleanArrayOf(), longArrayOf(2))),
            SourceDerivation.SourceWitness)

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `an invented finite box cannot become an original source premise`() {
        val model = Problem(0, IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(100),
            Bits(1).also { it.set(0) }, Bits(1).also { it.set(0) }), emptyArray())
        val context = assertNotNull(model.sourceProofContext(scope))

        assertFalse("int/0/lower" in context.premises)
        assertFalse("int/0/upper" in context.premises)
    }

    @Test
    fun `the original IR checker cannot relabel different declared source semantics`() {
        val decimal = scope.copy(revision = scope.revision.copy(semantics = "MPS-exact-decimal"))

        assertNull(model.sourceProofContext(decimal))
    }

    @Test
    fun `invocation capture owns the explicit source pins`() {
        val assumptions = Assumptions.None.withInt(0, 2)
        val condition = assumptions.sourceCondition()
        assumptions.intValues[0] = 3

        assertEquals(BigFraction.ofLong(2), condition.lower[SourceCoordinate(SourceCoordinate.Kind.Integer, 0)])
        assertEquals(BigFraction.ofLong(2), condition.upper[SourceCoordinate(SourceCoordinate.Kind.Integer, 0)])
    }

    @Test
    fun `an in memory witness token cannot rescue an invalid original assignment`() {
        val context = assertNotNull(model.sourceProofContext(scope))
        val sample = Sample(booleanArrayOf(), longArrayOf(1))
        val runtime = EngineInvocation(model.prepareExecutionPlan(), ModelIdentity.of(model))
        val publication = CompletedEnginePublication.Witness(runtime, sample, null)
        sample.witnessCertificate = EvidenceCertificate.published(publication)
        val evidence = SourceEvidence(scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(evidence))
    }


    @Test
    fun `an original affine source admits exact algebraic coordinates`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(2.0))
        val context = assertNotNull(model.sourceProofContext(scope))
        val value = AlgebraicArithmetic().roots(RationalPolynomial.of(-2, 0, 1)).last()
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), algebraicReals = listOf(value))

        val checked = SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness),
        )

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `a free original integer witness has no invented finite domain restriction`() {
        val model = Problem(0, IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(100),
            Bits(1).also { it.set(0) }, Bits(1).also { it.set(0) }), emptyArray())
        val context = assertNotNull(model.sourceProofContext(scope))
        val sample = Sample(booleanArrayOf(), longArrayOf(), exactInts = listOf(parseBigInt("9223372036854775808")))

        val checked = SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness),
        )

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `an original affine source admits a checked improving recession ray`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val scoped = scope.copy(objective = SourceObjective("minus-x", ObjectiveSense.MINIMIZE))
        val context = assertNotNull(model.sourceProofContext(scoped,
            LinearObjective(realCoefficients = doubleArrayOf(-1.0))))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), exactReals = listOf(BigFraction.ZERO))
        val claim = SourceClaim.Ray(sample, mapOf(SourceCoordinate(SourceCoordinate.Kind.Real, 0) to BigFraction.ONE))

        val checked = SourceEvidenceVerifier(context).verify(SourceEvidence(scoped, claim, SourceDerivation.SourceRay))

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

}
