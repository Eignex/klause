package com.eignex.klause.bench.profile

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceEvidenceProbeTest {
    @Test
    fun `a rational endpoint in a nonpoint root interval retains its exact value`() {
        val three = BigFraction.ofLong(3)

        val value = SourceEvidenceProbe.rationalRoot(listOf(BigFraction.ofLong(-9), BigFraction.ZERO, BigFraction.ONE),
            BigFraction.ofLong(2), BigFraction.ofLong(4), listOf(BigFraction.ofLong(-3), three))

        assertEquals(three, value)
    }

    @Test
    fun `a rational candidate inside an irrational root interval is withheld`() {
        val value = SourceEvidenceProbe.rationalRoot(listOf(BigFraction.ofLong(-2), BigFraction.ZERO, BigFraction.ONE),
            BigFraction.ZERO, BigFraction.ofLong(2), listOf(BigFraction.ONE))

        assertNull(value)
    }

    @Test
    fun `a different defining root outside the isolating interval is withheld`() {
        val value = SourceEvidenceProbe.rationalRoot(listOf(BigFraction.ofLong(-9), BigFraction.ZERO, BigFraction.ONE),
            BigFraction.ofLong(2), BigFraction.ofLong(4), listOf(BigFraction.ofLong(-3)))

        assertNull(value)
    }
}
