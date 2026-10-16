package com.eignex.klause.solver.validation

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.RealChoice
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertIs

class RealWitnessVerificationTest {
    @Test
    fun `real choice should check the exact arm selected by its signed guard`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(RealChoice(Lit.make(0, false), 0, 1, 2)),
            numRealVars = 3, realLower = DoubleArray(3), realUpper = DoubleArray(3) { 3.0 })
        for (truth in listOf(false, true)) {
            val point = Sample(booleanArrayOf(truth), longArrayOf(), doubleArrayOf(1.0, 1.0, 2.0),
                exactReals = listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ofLong(2)))

            val result = verifyRealCoordinates(source, point)

            if (truth) assertIs<Verification.Rejected>(result)
            else assertIs<Verification.Accepted<*, *>>(result)
        }
    }

    @Test
    fun `real choice should reject a witness without its Boolean guard`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(RealChoice(Lit.make(0, true), 0, 0, 0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), exactReals = listOf(BigFraction.ZERO))

        val result = verifyRealCoordinates(source, point)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `all different should reject wide collisions beside absent or certified real coordinates`() {
        val wide = parseBigInt("18446744073709551616")
        for (realCount in listOf(0, 1)) {
            val source = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                arrayOf(AllDifferent(intArrayOf(0, 1))),
                openIntLo = BooleanArray(2) { true }, openIntHi = BooleanArray(2) { true },
                numRealVars = realCount, realLower = DoubleArray(realCount), realUpper = DoubleArray(realCount))
            val point = Sample(booleanArrayOf(), longArrayOf(), DoubleArray(realCount),
                exactInts = listOf(wide, wide), exactReals = List(realCount) { BigFraction.ZERO })

            val result = verifyRealCoordinates(source, point)

            assertIs<Verification.Rejected>(result)
        }
    }

    @Test
    fun `all different should retain distinct integers sharing the same signed word projection`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(AllDifferent(intArrayOf(0, 1))),
            openIntLo = BooleanArray(2) { true }, openIntHi = BooleanArray(2) { true })
        val point = Sample(booleanArrayOf(), longArrayOf(),
            exactInts = listOf(parseBigInt("18446744073709551616"), bigIntOf(0)))

        val result = verifyRealCoordinates(source, point)

        assertIs<Verification.Accepted<*, *>>(result)
    }

    @Test
    fun `optional all different should check the original presence literal polarities`() {
        val wide = parseBigInt("18446744073709551616")
        val relation = AllDifferent(intArrayOf(0, 1), presents = intArrayOf(Lit.make(0, true), Lit.make(1, false)))
        val source = Problem(2, 2, Array(2) { IntDomain(0, 1) }, arrayOf(relation),
            openIntLo = BooleanArray(2) { true }, openIntHi = BooleanArray(2) { true })
        for (first in listOf(false, true)) {
            for (second in listOf(false, true)) {
                val point = Sample(booleanArrayOf(first, second), longArrayOf(), exactInts = listOf(wide, wide))

                val result = verifyRealCoordinates(source, point)

                if (first && !second) assertIs<Verification.Rejected>(result)
                else assertIs<Verification.Accepted<*, *>>(result)
            }
        }
    }

    @Test
    fun `all different should preserve exact exempt values at both signed word extrema`() {
        for (exempt in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val source = Problem(0, 2, Array(2) { IntDomain(exempt, exempt) },
                arrayOf(AllDifferent(intArrayOf(0, 1), exceptSet = longArrayOf(exempt))))
            val point = Sample(booleanArrayOf(), longArrayOf(exempt, exempt))

            val result = verifyRealCoordinates(source, point)

            assertIs<Verification.Accepted<*, *>>(result)
        }
    }

    @Test
    fun `all different should not wrap a wide collision into an exempt value`() {
        val wide = parseBigInt("18446744073709551616")
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf(AllDifferent(intArrayOf(0, 1), exceptSet = longArrayOf(0))),
            openIntLo = BooleanArray(2) { true }, openIntHi = BooleanArray(2) { true })
        val point = Sample(booleanArrayOf(), longArrayOf(), exactInts = listOf(wide, wide))

        val result = verifyRealCoordinates(source, point)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `all different should retain repeated source positions as a collision`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(AllDifferent(intArrayOf(0, 0))),
            openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))
        val point = Sample(booleanArrayOf(), longArrayOf(), exactInts = listOf(parseBigInt("18446744073709551616")))

        val result = verifyRealCoordinates(source, point)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `all different should reject missing original integer or presence coordinates`() {
        val wide = parseBigInt("18446744073709551616")
        val relation = AllDifferent(intArrayOf(0, 1), presents = intArrayOf(Lit.make(0, true), Lit.make(0, false)))
        val source = Problem(1, 2, Array(2) { IntDomain(0, 1) }, arrayOf(relation),
            openIntLo = BooleanArray(2) { true }, openIntHi = BooleanArray(2) { true })
        val incomplete = listOf(
            Sample(booleanArrayOf(), longArrayOf(), exactInts = listOf(wide, wide)),
            Sample(booleanArrayOf(true), longArrayOf(), exactInts = listOf(wide)),
        )
        for (point in incomplete) {
            val result = verifyRealCoordinates(source, point)

            assertIs<Verification.Rejected>(result)
        }
    }
}
