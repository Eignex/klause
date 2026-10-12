package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuadraticCandidatesTest {
    @Test
    fun `rational reconstruction certifies a coupled convex stationary point`() {
        val model = QuadraticModel(
            listOf(q(-4), q(-6)), listOf(listOf(q(2), q(1)), listOf(q(1), q(2))),
        )
        val checker = QuadraticCertification()

        val candidate = assertNotNull(QuadraticCandidates().stationary(model, emptyList()))
        val bound = assertNotNull(checker.bound(model, candidate))

        assertNotNull(checker.optimum(bound))
        assertEquals(listOf(q(2) * q(3).reciprocal(), q(8) * q(3).reciprocal()), candidate.anchor)
    }

    @Test
    fun `active source row reconstructs a boundary optimum`() {
        val model = QuadraticModel(
            listOf(q(0)), listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(-2), QuadraticRowRelation.LE)),
        )

        val candidate = assertNotNull(QuadraticCandidates().stationary(model, listOf(0)))
        val checker = QuadraticCertification()

        assertEquals(listOf(q(2)), candidate.anchor)
        assertNotNull(checker.optimum(assertNotNull(checker.bound(model, candidate))))
    }

    @Test
    fun `equality rows remain active without a numerical selection`() {
        val model = QuadraticModel(
            listOf(q(0)), listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(1)), q(3), QuadraticRowRelation.EQ)),
        )

        val candidate = assertNotNull(QuadraticCandidates().stationary(model, emptyList()))

        assertEquals(listOf(q(3)), candidate.anchor)
        assertEquals(listOf(q(-6)), candidate.multipliers)
    }

    @Test
    fun `inconsistent active equations produce no candidate`() {
        val model = QuadraticModel(
            listOf(q(0)), listOf(listOf(q(2))), rows = listOf(
                QuadraticRow(listOf(q(1)), q(2), QuadraticRowRelation.EQ),
                QuadraticRow(listOf(q(1)), q(3), QuadraticRowRelation.EQ),
            ),
        )

        assertNull(QuadraticCandidates().stationary(model, emptyList()))
    }

    @Test
    fun `numerical active proposal still requires exact certificate verification`() {
        val model = QuadraticModel(
            listOf(q(0)), listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(-2), QuadraticRowRelation.LE)),
        )
        val candidates = QuadraticCandidates()

        val active = assertNotNull(candidates.proposeActiveRows(model, listOf(0.0)))
        val candidate = assertNotNull(candidates.stationary(model, active))

        assertNotNull(QuadraticCertification().bound(model, candidate))
    }

    private fun q(value: Long): BigFraction = BigFraction.ofLong(value)
}
