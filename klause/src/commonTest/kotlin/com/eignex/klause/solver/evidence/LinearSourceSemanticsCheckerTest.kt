package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LinearSourceSemanticsCheckerTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(
        SourceRevision("source-hash", "exact-affine-v1"),
        objective = SourceObjective("objective-hash", ObjectiveSense.MINIMIZE),
    )
    private val lower = SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))
    private val checker = LinearSourceSemanticsChecker(setOf(x), listOf(lower), mapOf(x to BigFraction.MINUS_ONE))

    @Test
    fun `an exact rational point satisfies original source rows`() {
        val value = BigFraction.ofLong(3) / BigFraction.ofLong(2)
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(value.toDouble()), exactReals = listOf(value))

        assertEquals(SourceEvidenceCheck.Checked, checker.witness(sample, scope, Cancellation.Never))
    }

    @Test
    fun `an algebraic point is checked without rounded coordinate authority`() {
        val value = AlgebraicArithmetic().roots(RationalPolynomial.of(-2, 0, 1)).last()
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), algebraicReals = listOf(value))

        assertEquals(SourceEvidenceCheck.Checked, checker.witness(sample, scope, Cancellation.Never))
    }

    @Test
    fun `a rounded feasible value cannot replace missing exact coordinates`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0))

        assertIs<SourceEvidenceCheck.Unavailable>(checker.witness(sample, scope, Cancellation.Never))
    }

    @Test
    fun `a wrong point fails the original source check`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), exactReals = listOf(BigFraction.ZERO))

        assertIs<SourceEvidenceCheck.Rejected>(checker.witness(sample, scope, Cancellation.Never))
    }

    @Test
    fun `a source point must satisfy the actual invocation restrictions`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        val conditioned = scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ofLong(2))))

        assertIs<SourceEvidenceCheck.Rejected>(checker.witness(sample, conditioned, Cancellation.Never))
    }

    @Test
    fun `a valid feasible base and improving ray establish unboundedness`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        val claim = SourceClaim.Ray(sample, mapOf(x to BigFraction.ONE))

        assertEquals(SourceEvidenceCheck.Checked, checker.ray(claim, scope, Cancellation.Never))
    }

    @Test
    fun `a point alone does not prove unboundedness`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        val claim = SourceClaim.Ray(sample, emptyMap())

        assertIs<SourceEvidenceCheck.Rejected>(checker.ray(claim, scope, Cancellation.Never))
    }

    @Test
    fun `a ray cannot leave an invocation upper bound`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        val claim = SourceClaim.Ray(sample, mapOf(x to BigFraction.ONE))
        val conditioned = scope.copy(condition = SourceCondition(upper = mapOf(x to BigFraction.ofLong(2))))

        assertIs<SourceEvidenceCheck.Rejected>(checker.ray(claim, conditioned, Cancellation.Never))
    }

    @Test
    fun `an improving direction must preserve every original source row`() {
        val upper = SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(2)))
        val checker = LinearSourceSemanticsChecker(setOf(x), listOf(lower, upper), mapOf(x to BigFraction.MINUS_ONE))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))

        assertIs<SourceEvidenceCheck.Rejected>(checker.ray(SourceClaim.Ray(sample, mapOf(x to BigFraction.ONE)), scope, Cancellation.Never))
    }

    @Test
    fun `strict source rows reject their boundary`() {
        val row = SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE, strict = true))
        val checker = LinearSourceSemanticsChecker(setOf(x), listOf(row))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))

        assertIs<SourceEvidenceCheck.Rejected>(checker.witness(sample, scope, Cancellation.Never))
    }
}
