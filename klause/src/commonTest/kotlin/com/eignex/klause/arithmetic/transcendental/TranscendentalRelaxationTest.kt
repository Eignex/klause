package com.eignex.klause.arithmetic.transcendental

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertTrue

class TranscendentalRelaxationTest {
    @Test
    fun `exponential lower line remains valid on the negative tangent factor`() {
        val arithmetic = TranscendentalArithmetic()
        val line = arithmetic.expRelaxation(RationalEnclosure(q(-2), q(2)), q(0), 4)

        for (value in listOf(q(-2), q(-1), q(0), q(1), q(2))) {
            val enclosure = arithmetic.exp(RationalEnclosure.point(value), 8)
            assertTrue(line.lowerSlope * value + line.lowerIntercept <= enclosure.lower)
            assertTrue(line.upperSlope * value + line.upperIntercept >= enclosure.upper)
        }
    }

    @Test
    fun `log tangent and chord retain the declared positive interval`() {
        val arithmetic = TranscendentalArithmetic()
        val line = arithmetic.logRelaxation(RationalEnclosure(q(1), q(4)), q(2), 4)

        for (value in listOf(q(1), q(2), q(3), q(4))) {
            val enclosure = arithmetic.log(RationalEnclosure.point(value), 8)
            assertTrue(line.lowerSlope * value + line.lowerIntercept <= enclosure.lower)
            assertTrue(line.upperSlope * value + line.upperIntercept >= enclosure.upper)
        }
    }

    private fun q(value: Long): BigFraction = BigFraction.ofLong(value)
}
