package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuadraticCertificationTest {
    private val checker = QuadraticCertification()

    @Test
    fun `cross coefficients should retain their literal product value`() {
        val model = QuadraticModel(listOf(q(0), q(0)), listOf(listOf(q(2), q(3)), listOf(q(3), q(4))))

        val value = checker.evaluate(model, listOf(q(2), q(5)))

        assertEquals(q(84), value)
    }

    @Test
    fun `stationarity and convexity should certify an attained rational optimum`() {
        val model = QuadraticModel(listOf(q(-1)), listOf(listOf(q(2))))

        val bound = checker.bound(model, QuadraticStationaryCertificate(listOf(q(1) * q(2).reciprocal()), emptyList()))

        assertEquals(q(-1) * q(4).reciprocal(), assertNotNull(bound).value)
        assertNotNull(checker.optimum(bound))
    }

    @Test
    fun `dual bound should not require a feasible stationary anchor`() {
        val model = QuadraticModel(
            listOf(q(0)),
            listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(-2), QuadraticRowRelation.LE)),
        )

        val bound = checker.bound(model, QuadraticStationaryCertificate(listOf(q(1)), listOf(q(2))))

        assertEquals(q(3), assertNotNull(bound).value)
        assertNull(checker.optimum(bound))
    }

    @Test
    fun `wrong dual multiplier should not certify a stationary point`() {
        val model = QuadraticModel(listOf(q(-1)), listOf(listOf(q(2))))

        val bound = checker.bound(model, QuadraticStationaryCertificate(listOf(q(1)), emptyList()))

        assertNull(bound)
    }

    @Test
    fun `negative inequality multipliers should not certify a lower bound`() {
        val model = QuadraticModel(
            listOf(q(1)),
            listOf(listOf(q(0))),
            rows = listOf(QuadraticRow(listOf(q(1)), q(0), QuadraticRowRelation.LE)),
        )

        assertNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), listOf(q(-1)))))
    }

    @Test
    fun `nonconvex stationary point should not certify a global bound`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(-2))))

        assertNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), emptyList())))
    }

    @Test
    fun `zero diagonal with a nonzero cross term should not be positive semidefinite`() {
        val model = QuadraticModel(listOf(q(0), q(0)), listOf(listOf(q(0), q(1)), listOf(q(1), q(0))))

        assertEquals(false, checker.positiveSemidefinite(model))
    }

    @Test
    fun `rank deficient convex objective should retain a global certificate`() {
        val model = QuadraticModel(listOf(q(0), q(0)), listOf(listOf(q(2), q(2)), listOf(q(2), q(2))))

        assertNotNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(1), q(-1)), emptyList())))
    }

    @Test
    fun `strict source boundary should separate an unattained infimum from an optimum`() {
        val model = QuadraticModel(
            listOf(q(0)),
            listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(0), QuadraticRowRelation.LT)),
        )
        val bound = assertNotNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), listOf(q(0)))))

        val infimum = checker.infimum(bound, listOf(q(1)), q(1))

        assertNotNull(infimum)
        assertNull(checker.optimum(bound))
    }

    @Test
    fun `strict complementarity should certify a linear unattained infimum`() {
        val model = QuadraticModel(
            listOf(q(1)),
            listOf(listOf(q(0))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(0), QuadraticRowRelation.LT)),
        )
        val bound = assertNotNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), listOf(q(1)))))

        assertNotNull(checker.infimum(bound, listOf(q(1)), q(1)))
    }

    @Test
    fun `infeasible approach path should not establish an infimum`() {
        val model = QuadraticModel(
            listOf(q(0)),
            listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(0), QuadraticRowRelation.LT)),
        )
        val bound = assertNotNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), listOf(q(0)))))

        assertNull(checker.infimum(bound, listOf(q(-1)), q(1)))
    }

    @Test
    fun `another feasible minimizer should prevent a false nonattainment claim`() {
        val model = QuadraticModel(
            listOf(q(0)),
            listOf(listOf(q(0))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(0), QuadraticRowRelation.LT)),
        )
        val bound = assertNotNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), listOf(q(0)))))

        assertNull(checker.infimum(bound, listOf(q(1)), q(1)))
    }

    @Test
    fun `feasible improving recession ray should certify unboundedness`() {
        val model = QuadraticModel(
            listOf(q(-1)),
            listOf(listOf(q(0))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(0), QuadraticRowRelation.LT)),
        )

        assertNotNull(checker.unbounded(model, listOf(q(1)), listOf(q(1))))
    }

    @Test
    fun `negative curvature recession ray should certify nonconvex unboundedness`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(-2))))

        assertNotNull(checker.unbounded(model, listOf(q(0)), listOf(q(1))))
    }

    @Test
    fun `improving direction blocked by a source row should not certify unboundedness`() {
        val model = QuadraticModel(
            listOf(q(-1)),
            listOf(listOf(q(0))),
            rows = listOf(QuadraticRow(listOf(q(1)), q(2), QuadraticRowRelation.LE)),
        )

        assertNull(checker.unbounded(model, listOf(q(0)), listOf(q(1))))
    }

    @Test
    fun `positive curvature should not certify unlimited improvement`() {
        val model = QuadraticModel(listOf(q(-1)), listOf(listOf(q(2))))

        assertNull(checker.unbounded(model, listOf(q(0)), listOf(q(1))))
    }

    @Test
    fun `exhausted work allowance should stop before certificate checking`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(2))))
        val limited = QuadraticCertification(QuadraticControl(maximumWork = 0))

        val stop = assertFailsWith<QuadraticInterrupted> {
            limited.bound(model, QuadraticStationaryCertificate(listOf(q(0)), emptyList()))
        }

        assertEquals(QuadraticStop.WORK_LIMIT, stop.reason)
    }

    @Test
    fun `cancelled invocation should not publish a certificate`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(2))))
        val cancelled = QuadraticCertification(QuadraticControl(Cancellation { true }))

        val stop = assertFailsWith<QuadraticInterrupted> {
            cancelled.bound(model, QuadraticStationaryCertificate(listOf(q(0)), emptyList()))
        }

        assertEquals(QuadraticStop.CANCELLED, stop.reason)
    }

    @Test
    fun `matrix allocation cap should decline instead of truncating the objective`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(2))))
        val limited = QuadraticCertification(QuadraticControl(maximumMatrixCells = 0))

        val stop = assertFailsWith<QuadraticInterrupted> { limited.positiveSemidefinite(model) }

        assertEquals(QuadraticStop.MATRIX_LIMIT, stop.reason)
    }

    private fun q(value: Long): BigFraction = BigFraction.ofLong(value)
}
