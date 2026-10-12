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
    fun `cross coefficients retain their literal product value`() {
        val model = QuadraticModel(listOf(q(0), q(0)), listOf(listOf(q(2), q(3)), listOf(q(3), q(4))))

        val value = checker.evaluate(model, listOf(q(2), q(5)))

        assertEquals(q(84), value)
    }

    @Test
    fun `stationarity and convexity certify an attained rational optimum`() {
        val model = QuadraticModel(listOf(q(-1)), listOf(listOf(q(2))))

        val bound = checker.bound(model, QuadraticStationaryCertificate(listOf(q(1) * q(2).reciprocal()), emptyList()))

        assertEquals(q(-1) * q(4).reciprocal(), assertNotNull(bound).value)
        assertNotNull(checker.optimum(bound))
    }

    @Test
    fun `a dual bound does not require a feasible stationary anchor`() {
        val model = QuadraticModel(
            listOf(q(0)), listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(-2), QuadraticRowRelation.LE)),
        )

        val bound = checker.bound(model, QuadraticStationaryCertificate(listOf(q(1)), listOf(q(2))))

        assertEquals(q(3), assertNotNull(bound).value)
        assertNull(checker.optimum(bound))
    }

    @Test
    fun `wrong dual multiplier cannot certify a stationary point`() {
        val model = QuadraticModel(listOf(q(-1)), listOf(listOf(q(2))))

        val bound = checker.bound(model, QuadraticStationaryCertificate(listOf(q(1)), emptyList()))

        assertNull(bound)
    }

    @Test
    fun `negative inequality multipliers cannot certify a lower bound`() {
        val model = QuadraticModel(
            listOf(q(1)), listOf(listOf(q(0))),
            rows = listOf(QuadraticRow(listOf(q(1)), q(0), QuadraticRowRelation.LE)),
        )

        assertNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), listOf(q(-1)))))
    }

    @Test
    fun `a nonconvex stationary point cannot certify a global bound`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(-2))))

        assertNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), emptyList())))
    }

    @Test
    fun `zero diagonal with a nonzero cross term is not positive semidefinite`() {
        val model = QuadraticModel(listOf(q(0), q(0)), listOf(listOf(q(0), q(1)), listOf(q(1), q(0))))

        assertEquals(false, checker.positiveSemidefinite(model))
    }

    @Test
    fun `rank deficient convex objective retains a global certificate`() {
        val model = QuadraticModel(listOf(q(0), q(0)), listOf(listOf(q(2), q(2)), listOf(q(2), q(2))))

        assertNotNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(1), q(-1)), emptyList())))
    }

    @Test
    fun `strict source boundary separates unattained infimum from optimum`() {
        val model = QuadraticModel(
            listOf(q(0)), listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(0), QuadraticRowRelation.LT)),
        )
        val bound = assertNotNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), listOf(q(0)))))

        val infimum = checker.infimum(bound, listOf(q(1)), q(1))

        assertNotNull(infimum)
        assertNull(checker.optimum(bound))
    }

    @Test
    fun `strict complementarity certifies a linear unattained infimum`() {
        val model = QuadraticModel(
            listOf(q(1)), listOf(listOf(q(0))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(0), QuadraticRowRelation.LT)),
        )
        val bound = assertNotNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), listOf(q(1)))))

        assertNotNull(checker.infimum(bound, listOf(q(1)), q(1)))
    }

    @Test
    fun `an infeasible approach path cannot establish infimum`() {
        val model = QuadraticModel(
            listOf(q(0)), listOf(listOf(q(2))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(0), QuadraticRowRelation.LT)),
        )
        val bound = assertNotNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), listOf(q(0)))))

        assertNull(checker.infimum(bound, listOf(q(-1)), q(1)))
    }

    @Test
    fun `another feasible minimizer prevents a false nonattainment claim`() {
        val model = QuadraticModel(
            listOf(q(0)), listOf(listOf(q(0))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(0), QuadraticRowRelation.LT)),
        )
        val bound = assertNotNull(checker.bound(model, QuadraticStationaryCertificate(listOf(q(0)), listOf(q(0)))))

        assertNull(checker.infimum(bound, listOf(q(1)), q(1)))
    }

    @Test
    fun `feasible improving recession ray certifies unboundedness`() {
        val model = QuadraticModel(
            listOf(q(-1)), listOf(listOf(q(0))),
            rows = listOf(QuadraticRow(listOf(q(-1)), q(0), QuadraticRowRelation.LT)),
        )

        assertNotNull(checker.unbounded(model, listOf(q(1)), listOf(q(1))))
    }

    @Test
    fun `negative curvature recession ray certifies nonconvex unboundedness`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(-2))))

        assertNotNull(checker.unbounded(model, listOf(q(0)), listOf(q(1))))
    }

    @Test
    fun `improving direction blocked by a source row cannot certify unboundedness`() {
        val model = QuadraticModel(
            listOf(q(-1)), listOf(listOf(q(0))),
            rows = listOf(QuadraticRow(listOf(q(1)), q(2), QuadraticRowRelation.LE)),
        )

        assertNull(checker.unbounded(model, listOf(q(0)), listOf(q(1))))
    }

    @Test
    fun `positive curvature cannot certify unlimited improvement`() {
        val model = QuadraticModel(listOf(q(-1)), listOf(listOf(q(2))))

        assertNull(checker.unbounded(model, listOf(q(0)), listOf(q(1))))
    }

    @Test
    fun `exhausted work allowance stops before certificate checking`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(2))))
        val limited = QuadraticCertification(QuadraticControl(maximumWork = 0))

        val stop = assertFailsWith<QuadraticInterrupted> {
            limited.bound(model, QuadraticStationaryCertificate(listOf(q(0)), emptyList()))
        }

        assertEquals(QuadraticStop.WORK_LIMIT, stop.reason)
    }

    @Test
    fun `cancelled invocation cannot publish a certificate`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(2))))
        val cancelled = QuadraticCertification(QuadraticControl(Cancellation { true }))

        val stop = assertFailsWith<QuadraticInterrupted> {
            cancelled.bound(model, QuadraticStationaryCertificate(listOf(q(0)), emptyList()))
        }

        assertEquals(QuadraticStop.CANCELLED, stop.reason)
    }

    @Test
    fun `matrix allocation cap declines instead of truncating the objective`() {
        val model = QuadraticModel(listOf(q(0)), listOf(listOf(q(2))))
        val limited = QuadraticCertification(QuadraticControl(maximumMatrixCells = 0))

        val stop = assertFailsWith<QuadraticInterrupted> { limited.positiveSemidefinite(model) }

        assertEquals(QuadraticStop.MATRIX_LIMIT, stop.reason)
    }

    private fun q(value: Long): BigFraction = BigFraction.ofLong(value)
}
