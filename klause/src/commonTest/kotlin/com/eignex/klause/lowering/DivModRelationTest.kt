package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DivModRelationTest {
    @Test
    fun `repeated constant divisors should reuse both outputs without allocation`() {
        for (divisor in listOf(Long.MIN_VALUE, -3L, 3L, Long.MAX_VALUE)) {
            val index = DivModRelationIndex()
            val first = index.retain(
                0,
                1,
                IntDomain(-7, 7),
                IntDomain(divisor, divisor),
                2,
                3,
                { error("unexpected allocation") },
            )

            repeat(4) { offset ->
                val reused = index.retain(
                    0,
                    10 + offset,
                    IntDomain(-7, 7),
                    IntDomain(divisor, divisor),
                    if (offset % 2 == 0) first.quotient else null,
                    first.remainder,
                    { error("unexpected allocation") },
                )

                assertEquals(first.quotient, reused.quotient)
                assertEquals(first.remainder, reused.remainder)
                assertTrue(reused.factors.isEmpty())
            }
        }
    }

    @Test
    fun `variable divisors should reuse outputs only within their compilation`() {
        val index = DivModRelationIndex()
        val first = index.retain(
            0,
            1,
            IntDomain(-7, 7),
            IntDomain(-3, 3),
            2,
            3,
            { error("unexpected allocation") },
        )

        val reused = index.retain(
            0,
            1,
            IntDomain(-7, 7),
            IntDomain(-3, 3),
            null,
            null,
            { error("unexpected allocation") },
        )

        assertEquals(first.quotient, reused.quotient)
        assertEquals(first.remainder, reused.remainder)
        assertTrue(reused.factors.isEmpty())
    }

    @Test
    fun `relation keys should separate operand identities constant values and conventions`() {
        val index = DivModRelationIndex()
        val cases = listOf(
            Triple(0, 1, IntDomain(2, 4)),
            Triple(0, 2, IntDomain(2, 4)),
            Triple(1, 2, IntDomain(2, 4)),
            Triple(0, 1, IntDomain(3, 3)),
            Triple(0, 1, IntDomain(-3, -3)),
        )

        for (convention in DivisionConvention.entries) {
            for ((a, b, domain) in cases) {
                val relation = index.retain(
                    a,
                    b,
                    IntDomain(-7, 7),
                    domain,
                    3,
                    4,
                    { error("unexpected allocation") },
                    convention,
                )

                val factor = assertIs<DivMod>(relation.factors.single())
                assertEquals(a, factor.dividend)
                assertEquals(convention, factor.convention)
                if (domain.min == domain.max) {
                    assertEquals(bigIntOf(domain.min), factor.constantDivisor)
                } else {
                    assertEquals(b, factor.divisor)
                }
            }
        }
    }

    @Test
    fun `reused signed relation outputs should channel every requested alias`() {
        for ((convention, expected) in listOf(
            DivisionConvention.TRUNCATING to (2L to -1L),
            DivisionConvention.EUCLIDEAN to (3L to 2L),
        )) {
            val index = DivModRelationIndex()
            val first = index.retain(
                0,
                1,
                IntDomain(-7, -7),
                IntDomain(-3, -3),
                2,
                3,
                { error("unexpected allocation") },
                convention,
            )
            val reused = index.retain(
                0,
                1,
                IntDomain(-7, -7),
                IntDomain(-3, -3),
                4,
                5,
                { error("unexpected allocation") },
                convention,
            )
            val problem = Problem(
                0,
                6,
                arrayOf(
                    IntDomain(-7, -7),
                    IntDomain(-3, -3),
                    IntDomain(-8, 8),
                    IntDomain(-3, 3),
                    IntDomain(-8, 8),
                    IntDomain(-3, 3),
                ),
                (first.factors + reused.factors).toTypedArray(),
            )

            val session = PropagationSession(problem)

            assertFalse(session.isUnsatAtRoot)
            assertEquals(IntDomain(expected.first, expected.first), session.intDomain(4))
            assertEquals(IntDomain(expected.second, expected.second), session.intDomain(5))
        }
    }

    @Test
    fun `new compilation index should retain its own output coordinates`() {
        val first = DivModRelationIndex().retain(
            0,
            1,
            IntDomain(-7, 7),
            IntDomain(3, 3),
            2,
            3,
            { error("unexpected allocation") },
        )

        val second = DivModRelationIndex().retain(
            0,
            1,
            IntDomain(-7, 7),
            IntDomain(3, 3),
            4,
            5,
            { error("unexpected allocation") },
        )

        assertEquals(2, first.quotient)
        assertEquals(4, assertIs<DivMod>(second.factors.single()).quotient)
        assertEquals(5, second.remainder)
    }
}
