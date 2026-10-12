package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceClaimMatchingTest {
    @Test
    fun `algebraic witness matching uses exact coordinates rather than telemetry`() {
        val roots = AlgebraicArithmetic().roots(RationalPolynomial.of(-2, 0, 1))
        val first = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), algebraicReals = listOf(roots.last()))
        val equivalent = AlgebraicArithmetic().roots(RationalPolynomial.of(-2, 0, 1)).last()
        val second = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(100.0), algebraicReals = listOf(equivalent))

        assertEquals(SourceEvidenceCheck.Checked, matchSourceClaims(
            SourceClaim.Witness(first), SourceClaim.Witness(second), Cancellation.Never,
        ))
    }

    @Test
    fun `equal telemetry cannot hide a different exact algebraic witness`() {
        val roots = AlgebraicArithmetic().roots(RationalPolynomial.of(-2, 0, 1))
        val first = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), algebraicReals = listOf(roots.last()))
        val second = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), algebraicReals = listOf(roots.first()))

        assertIs<SourceEvidenceCheck.Rejected>(matchSourceClaims(
            SourceClaim.Witness(first), SourceClaim.Witness(second), Cancellation.Never,
        ))
    }

    @Test
    fun `equivalent rational and algebraic bounds match exactly`() {
        val value = AlgebraicArithmetic().rational(BigFraction.ONE)
        val first = SourceClaim.Bound(SourceValue.Algebraic(value))
        val second = SourceClaim.Bound(BigFraction.ONE)

        assertEquals(SourceEvidenceCheck.Checked, matchSourceClaims(first, second, Cancellation.Never))
    }

    @Test
    fun `missing exact real coordinates leave claim matching unavailable`() {
        val first = SourceClaim.Witness(Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0)))
        val second = SourceClaim.Witness(Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0)))

        assertIs<SourceEvidenceCheck.Unavailable>(matchSourceClaims(first, second, Cancellation.Never))
    }

    @Test
    fun `a different exact objective bound cannot reuse captured evidence`() {
        val first = SourceClaim.Bound(BigFraction.ONE)
        val second = SourceClaim.Bound(BigFraction.ofLong(2))

        assertIs<SourceEvidenceCheck.Rejected>(matchSourceClaims(first, second, Cancellation.Never))
    }
}
