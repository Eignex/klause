package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceMixedIntegerRoundingTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val half = BigFraction.ofLong(2).reciprocal()
    private val scope = SourceScope(SourceRevision("mixed", "exact"))

    @Test
    fun `zero endpoints should preserve checked cuts within their arithmetic allowance`() {
        for ((coefficient, boundCoefficient) in listOf(
            BigFraction.MINUS_ONE to BigFraction.ofLong(-2),
            BigFraction.ONE to BigFraction.ofLong(2),
        )) {
            val row = SourceInequality(mapOf(x to BigFraction.ONE, y to coefficient), half)
            val bound = SourceInequality(mapOf(y to boundCoefficient), BigFraction.ZERO)
            var remaining = 7

            val cut = assertNotNull(mixedIntegerRounding(row, listOf(bound), Cancellation.Never) { _, _ ->
                check(remaining-- > 0)
            })

            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to coefficient * BigFraction.ofLong(2)),
                BigFraction.ZERO), cut)
            val source = SourceProofContext(scope, setOf(x, y), mapOf(
                "mixed" to SourcePremise(row), "bound" to SourcePremise(bound)))
            val proof = SourceDerivation.MixedIntegerRounding(SourceDerivation.Premise("mixed"),
                listOf(SourceDerivation.Premise("bound")))
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut), proof)))
        }
    }

    @Test
    fun `discarded residuals at nonzero endpoints should fit their arithmetic allowance`() {
        for ((coefficient, boundCoefficient, endpoint) in listOf(
            Triple(BigFraction.ONE, BigFraction.ofLong(-2), BigFraction.ofLong(3)),
            Triple(BigFraction.MINUS_ONE, BigFraction.ofLong(2), BigFraction.ofLong(-3)),
        )) {
            val row = SourceInequality(mapOf(x to BigFraction.ONE, y to coefficient),
                coefficient * endpoint + half)
            val bound = SourceInequality(mapOf(y to boundCoefficient), boundCoefficient * endpoint)
            var remaining = 8

            val cut = mixedIntegerRounding(row, listOf(bound), Cancellation.Never) { _, _ ->
                check(remaining-- > 0)
            }

            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO), cut)
        }
    }

    @Test
    fun `nonzero retained residuals should still require their arithmetic allowance`() {
        val row = SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE),
            BigFraction.ofLong(-3) + half)
        val bound = SourceInequality(mapOf(y to BigFraction.ofLong(-2)), BigFraction.ofLong(-6))
        for (allowance in listOf(7, 11)) {
            var remaining = allowance

            val cut = mixedIntegerRounding(row, listOf(bound), Cancellation { remaining < 0 }) { _, _ ->
                remaining--
            }

            if (allowance == 7) assertNull(cut) else {
                assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(-2)),
                    BigFraction.ofLong(-6)), cut)
            }
        }
    }

    @Test
    fun `zero endpoints should retain the bound scalar limit`() {
        val source = SourceProofContext(scope, setOf(x, y), mapOf(
            "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE), half)),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.of(BIG_ONE shl 4096, BIG_ONE).negated()),
                BigFraction.ZERO)),
        ))
        val proof = SourceDerivation.MixedIntegerRounding(SourceDerivation.Premise("mixed"),
            listOf(SourceDerivation.Premise("bound")))
        val conclusion = SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(-2)), BigFraction.ZERO)

        val check = SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(conclusion), proof))

        assertIs<SourceEvidenceCheck.Unavailable>(check)
    }

    @Test
    fun `cancelled zero endpoint rounding should publish no inequality`() {
        val row = SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE), half)
        val bound = SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)
        var cancelled = false

        val cut = mixedIntegerRounding(row, listOf(bound), Cancellation { cancelled }) { _, _ -> cancelled = true }

        assertNull(cut)
    }

    @Test
    fun `rounding should shift and complement continuous coordinates exactly`() {
        for ((coefficient, boundCoefficient, endpoint) in listOf(
            Triple(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ofLong(3)),
            Triple(BigFraction.ONE, BigFraction.ONE, BigFraction.ofLong(-3)),
        )) {
            val row = SourceInequality(mapOf(x to BigFraction.ONE, y to coefficient),
                coefficient * endpoint + half)
            val bound = SourceInequality(mapOf(y to boundCoefficient), boundCoefficient * endpoint)

            val cut = assertNotNull(mixedIntegerRounding(row, listOf(bound), Cancellation.Never))

            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to coefficient * BigFraction.ofLong(2)),
                coefficient * BigFraction.ofLong(2) * endpoint), cut)
        }
    }

    @Test
    fun `positive continuous residuals should drop without rounding their coordinates`() {
        val row = SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), half)
        val bound = SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)

        val cut = mixedIntegerRounding(row, listOf(bound), Cancellation.Never)

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO), cut)
    }

    @Test
    fun `rounding should decline fractional integer activity and invalid bounds`() {
        val row = SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE), half)
        val bound = SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)
        for ((candidate, bounds) in listOf(
            row to emptyList(),
            row to listOf(bound, bound),
            row to listOf(SourceInequality(emptyMap(), BigFraction.ZERO)),
            row to listOf(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            row to listOf(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            row to listOf(SourceInequality(mapOf(SourceCoordinate(SourceCoordinate.Kind.Real, 1) to BigFraction.ONE),
                BigFraction.ZERO)),
            SourceInequality(mapOf(x to half, y to BigFraction.MINUS_ONE), half) to listOf(bound),
            SourceInequality(row.coefficients, BigFraction.ONE) to listOf(bound),
        )) {
            assertNull(mixedIntegerRounding(candidate, bounds, Cancellation.Never))
        }
    }

    @Test
    fun `mixed rounding verification should reject a strengthened conclusion`() {
        val source = SourceProofContext(scope, setOf(x, y), mapOf(
            "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE), half)),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val proof = SourceDerivation.MixedIntegerRounding(SourceDerivation.Premise("mixed"),
            listOf(SourceDerivation.Premise("bound")))
        val forged = SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(-2)), BigFraction.MINUS_ONE)

        val check = SourceEvidenceVerifier(source).verify(SourceEvidence(scope, SourceClaim.Consequence(forged), proof))

        assertIs<SourceEvidenceCheck.Rejected>(check)
    }

    @Test
    fun `mixed rounding should withhold a proof when an intermediate exceeds the scalar allowance`() {
        val endpoint = BigFraction.of(BIG_ONE shl 4095, BIG_ONE)
        val source = SourceProofContext(scope, setOf(x, y), mapOf(
            "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE), half)),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), endpoint)),
        ))
        val proof = SourceDerivation.MixedIntegerRounding(SourceDerivation.Premise("mixed"),
            listOf(SourceDerivation.Premise("bound")))
        val conclusion = SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(-2)), endpoint)

        val check = SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(conclusion), proof))

        assertIs<SourceEvidenceCheck.Unavailable>(check)
    }
}
