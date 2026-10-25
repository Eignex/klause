package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.shl
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
    fun `rounding should decline fractional integer activity and missing or repeated bounds`() {
        val row = SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE), half)
        val bound = SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)
        for ((candidate, bounds) in listOf(
            row to emptyList(),
            row to listOf(bound, bound),
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

        val check = SourceEvidenceVerifier(source).verify(SourceEvidence(scope, SourceClaim.Consequence(conclusion), proof))

        assertIs<SourceEvidenceCheck.Unavailable>(check)
    }
}
