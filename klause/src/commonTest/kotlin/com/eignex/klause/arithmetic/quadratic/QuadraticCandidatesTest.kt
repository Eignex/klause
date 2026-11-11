package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuadraticCandidatesTest {
    @Test
    fun `rational reconstruction should certify a coupled convex stationary point`() {
        val model = QuadraticModel(
            listOf(q(-4), q(-6)),
            listOf(listOf(q(2), q(1)), listOf(q(1), q(2))),
        )
        val checker = QuadraticCertification()

        val candidate = assertNotNull(QuadraticCandidates().stationary(model, emptyList()))
        val bound = assertNotNull(checker.bound(model, candidate))

        assertNotNull(checker.optimum(bound))
        assertEquals(listOf(q(2) * q(3).reciprocal(), q(8) * q(3).reciprocal()), candidate.anchor)
    }

    @Test
    fun `active source row should reconstruct a boundary optimum`() {
        val model = QuadraticModel(
            listOf(q(0)),
            listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(-2), QuadraticRowRelation.LE)),
        )

        val candidate = assertNotNull(QuadraticCandidates().stationary(model, listOf(0)))
        val checker = QuadraticCertification()

        assertEquals(listOf(q(2)), candidate.anchor)
        assertNotNull(checker.optimum(assertNotNull(checker.bound(model, candidate))))
    }

    @Test
    fun `equality rows should remain active without a numerical selection`() {
        val model = QuadraticModel(
            listOf(q(0)),
            listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(1)), q(3), QuadraticRowRelation.EQ)),
        )

        val candidate = assertNotNull(QuadraticCandidates().stationary(model, emptyList()))

        assertEquals(listOf(q(3)), candidate.anchor)
        assertEquals(listOf(q(-6)), candidate.multipliers)
    }

    @Test
    fun `inconsistent active equations should produce no candidate`() {
        val model = QuadraticModel(
            listOf(q(0)),
            listOf(listOf(q(2))),
            rows = listOf(
                QuadraticRow(listOf(q(1)), q(2), QuadraticRowRelation.EQ),
                QuadraticRow(listOf(q(1)), q(3), QuadraticRowRelation.EQ),
            ),
        )

        assertNull(QuadraticCandidates().stationary(model, emptyList()))
    }

    @Test
    fun `numerical active proposal should still require exact certificate verification`() {
        val model = QuadraticModel(
            listOf(q(0)),
            listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(-2), QuadraticRowRelation.LE)),
        )
        val candidates = QuadraticCandidates()

        val active = assertNotNull(candidates.proposeActiveRows(model, listOf(0.0)))
        val candidate = assertNotNull(candidates.stationary(model, active))

        assertNotNull(QuadraticCertification().bound(model, candidate))
    }

    @Test
    fun `coupled numerical rows should reconstruct a certified constrained optimum`() {
        val model = QuadraticModel(
            listOf(q(-4), q(-6)),
            listOf(listOf(q(2), q(1)), listOf(q(1), q(2))),
            rows = listOf(
                QuadraticRow(listOf(q(-1), q(0)), q(-2), QuadraticRowRelation.LE),
                QuadraticRow(listOf(q(0), q(-1)), q(10), QuadraticRowRelation.LE),
                QuadraticRow(listOf(q(1), q(1)), q(5), QuadraticRowRelation.EQ),
            ),
        )
        val candidates = QuadraticCandidates()
        val checker = QuadraticCertification()

        val active = assertNotNull(candidates.proposeActiveRows(model, listOf(0.0, 5.0)))
        val candidate = assertNotNull(candidates.stationary(model, active))
        val optimum = assertNotNull(checker.optimum(assertNotNull(checker.bound(model, candidate))))

        assertEquals(listOf(q(2), q(3)), optimum.point)
    }

    @Test
    fun `numerical rows should include a changed coordinate beyond a kernel slice`() {
        val dimension = 257
        val coefficients = List(dimension) { if (it == dimension - 1) q(1) else q(0) }
        val model = QuadraticModel(
            List(dimension) { q(0) },
            List(dimension) { row -> List(dimension) { column -> if (row == column) q(2) else q(0) } },
            rows = listOf(
                QuadraticRow(coefficients.map { it.negated() }, q(-2), QuadraticRowRelation.LE),
                QuadraticRow(coefficients, q(1) * q(2).reciprocal(), QuadraticRowRelation.LE),
            ),
        )

        val active = QuadraticCandidates().proposeActiveRows(model, List(dimension) { 0.0 }, iterations = 1)

        assertEquals(listOf(0, 1), active)
    }

    @Test
    fun `numerical proposal should charge its containing work meter`() {
        val model = QuadraticModel(
            listOf(q(0)), listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(-2), QuadraticRowRelation.LE)),
        )
        var charged = 0L
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val candidates = QuadraticCandidates(QuadraticControl(token))

        assertNotNull(candidates.proposeActiveRows(model, listOf(0.0), iterations = 1))

        assertEquals(7L, charged)
    }

    @Test
    fun `exhausting work inside a numerical slice should withhold the proposal`() {
        val model = QuadraticModel(listOf(q(0), q(0)), listOf(listOf(q(2), q(0)), listOf(q(0), q(2))))
        val control = QuadraticControl(maximumWork = 2)

        val stop = assertFailsWith<QuadraticInterrupted> {
            QuadraticCandidates(control).proposeActiveRows(model, listOf(1.0, 1.0), iterations = 1)
        }

        assertEquals(QuadraticStop.WORK_LIMIT, stop.reason)
        assertEquals(2L, control.work)
    }

    @Test
    fun `cancelling inside a numerical slice should withhold the proposal`() {
        val model = QuadraticModel(listOf(q(0), q(0)), listOf(listOf(q(2), q(0)), listOf(q(0), q(2))))
        var charged = 0L
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 2L
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val stop = assertFailsWith<QuadraticInterrupted> {
            QuadraticCandidates(QuadraticControl(token)).proposeActiveRows(model, listOf(1.0, 1.0), iterations = 1)
        }

        assertEquals(QuadraticStop.CANCELLED, stop.reason)
        assertEquals(2L, charged)
    }

    @Test
    fun `nonfinite starting coordinates should decline a numerical proposal`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(2))))

        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertNull(QuadraticCandidates().proposeActiveRows(model, listOf(value)))
        }
    }

    @Test
    fun `nonfinite gradients should decline a numerical proposal`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(2))))

        assertNull(QuadraticCandidates().proposeActiveRows(model, listOf(Double.MAX_VALUE), iterations = 1))
    }

    @Test
    fun `nonfinite final row images should decline a numerical proposal`() {
        val model = QuadraticModel(
            listOf(q(0)), listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(2)), q(0), QuadraticRowRelation.LE)),
        )

        assertNull(QuadraticCandidates().proposeActiveRows(model, listOf(Double.MAX_VALUE), iterations = 0))
    }

    private fun q(value: Long): BigFraction = BigFraction.ofLong(value)
}
