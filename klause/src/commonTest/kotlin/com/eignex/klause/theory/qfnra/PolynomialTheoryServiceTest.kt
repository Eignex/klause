package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialTheoryServiceTest {
    @Test
    fun `completed query should have no further elements`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        service.invocation().use { invocation ->
            val pending = invocation.prepareCheck()
            var result: QfNraResult? = null
            while (result == null) result = pending.next()

            assertFalse(pending.hasNext())
            assertFailsWith<NoSuchElementException> { pending.next() }
        }
    }

    @Test
    fun `projection continuation should retain native charges and refutation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (= y (* x x)))
            (assert (= (* x x) 2))
            (assert (= (* y y) 3))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val complete = service.invocation().use { assertIs<QfNraResult.Unsatisfiable>(it.check()) }
        assertTrue(complete.stats.projectedPolynomials > 0)

        service.invocation(QfNraParams(maximumWork = complete.stats.workSpent + 1)).use { invocation ->
            val pending = invocation.prepareCheck()
            var result: QfNraResult? = null
            var pauses = 0
            while (result == null) {
                result = pending.next()
                if (result == null) pauses++
            }

            assertIs<QfNraResult.Unsatisfiable>(result)
            assertTrue(pauses > 2)
            assertEquals(complete.stats, result.stats)
            assertEquals(complete.stats, invocation.stats)
            assertEquals(1L, invocation.queries)
        }
    }

    @Test
    fun `paused query should retain native work without repeating preparation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val required = service.invocation().use { it.check().stats.workSpent }

        service.invocation(QfNraParams(maximumWork = required + 1)).use { invocation ->
            val pending = invocation.prepareCheck()
            assertNull(pending.next())
            val preparedWork = invocation.stats.workSpent
            assertTrue(preparedWork > 0)
            var result: QfNraResult? = null
            while (result == null) result = pending.next()

            assertIs<QfNraResult.Satisfiable>(result)
            assertEquals(required, invocation.stats.workSpent)
            assertEquals(1L, invocation.queries)
            assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(invocation.check()).reason)
        }
    }

    @Test
    fun `closing paused query should retain charges and reject continuation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val invocation = PolynomialTheoryService(source.formula, source.boolCount, source.realCount).invocation()
        val pending = invocation.prepareCheck()
        assertNull(pending.next())
        val charged = invocation.stats.workSpent

        invocation.close()
        invocation.close()

        assertEquals(charged, invocation.stats.workSpent)
        assertFailsWith<IllegalStateException> { pending.next() }
    }

    @Test
    fun `solve cancellation should terminate paused query without a partial witness`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        var cancelled = false
        service.invocation(QfNraParams(cancellation = Cancellation { cancelled })).use { invocation ->
            val pending = invocation.prepareCheck()
            assertNull(pending.next())
            cancelled = true

            val result = pending.next()

            assertEquals(QfNraReason.Interrupted, assertIs<QfNraResult.Unknown>(result).reason)
        }
    }

    @Test
    fun `query bounds should not replace fixed source coordinates`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert true)")
        val service = PolynomialTheoryService(source.formula, 0, 1, mapOf(0 to BigFraction.ONE))
        val contradictory = PolynomialCondition(
            lower = mapOf(0 to BigFraction.ofLong(2)),
            upper = mapOf(0 to BigFraction.ofLong(2)),
        )

        service.invocation().use { invocation ->
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(contradictory))
            val witness = assertIs<QfNraResult.Satisfiable>(invocation.check()).assignment
            assertEquals(BigFraction.ONE, witness.realValue(0).rational)
        }
    }

    @Test
    fun `conditioned queries should isolate finite pins`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const k Real)
            (assert (= (* x x) k))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            val negative = PolynomialCondition(
                lower = mapOf(1 to BigFraction.MINUS_ONE),
                upper = mapOf(1 to BigFraction.MINUS_ONE),
            )
            val positive = PolynomialCondition(
                lower = mapOf(1 to BigFraction.ofLong(2)),
                upper = mapOf(1 to BigFraction.ofLong(2)),
            )
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(negative))
            val witness = assertIs<QfNraResult.Satisfiable>(invocation.check(positive)).assignment
            assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), witness.realValue(0)))
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(negative))
        }
    }

    @Test
    fun `Boolean query conditions should preserve correlation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const b Bool)
            (declare-const x Real)
            (assert (or b (= (* x x) (- 1))))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(PolynomialCondition(bools = mapOf(0 to false))))
            val witness = assertIs<QfNraResult.Satisfiable>(
                invocation.check(PolynomialCondition(bools = mapOf(0 to true))),
            ).assignment
            assertEquals(true, witness.boolValue(0))
        }
    }

    @Test
    fun `native work should reach caller meter once`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        var charged = 0L
        val token = object : Cancellation, WorkMeter {
            override fun isCancelled() = false
            override fun workMeter(): WorkMeter = this
            override fun charge(units: Long) {
                charged += units
            }
        }

        service.invocation(QfNraParams(cancellation = token)).use { invocation ->
            invocation.check()
            invocation.check()
            assertEquals(invocation.stats.workSpent, charged)
        }
    }

    @Test
    fun `closing an invocation should preserve published coordinates`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val invocation = service.invocation()
        val witness = assertIs<QfNraResult.Satisfiable>(invocation.check()).assignment

        invocation.close()
        invocation.close()
        assertFailsWith<IllegalStateException> { invocation.check() }
        assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), witness.realValue(0)))
    }

    @Test
    fun `cancelled query should not contaminate a fresh invocation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            assertEquals(
                QfNraReason.Interrupted,
                assertIs<QfNraResult.Unknown>(invocation.check(cancellation = Cancellation { true })).reason,
            )
        }
        service.invocation().use { assertIs<QfNraResult.Satisfiable>(it.check()) }
    }

    @Test
    fun `compatible Boolean conditions should reuse a witness within the remaining allowance`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const b Bool) (declare-const x Real) (assert b) (assert (= (* x x) 2))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val initial = service.invocation().use { it.check().stats.workSpent }

        service.invocation(QfNraParams(maximumWork = initial + 10)).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            val repeated = invocation.check(PolynomialCondition(bools = mapOf(0 to true)))

            assertTrue(assertIs<QfNraResult.Satisfiable>(repeated).assignment.boolValue(0))
            assertEquals(0L, repeated.stats.projectedPolynomials)
            assertEquals(initial + repeated.stats.workSpent, invocation.stats.workSpent)
            assertEquals(2L, invocation.queries)
        }
    }

    @Test
    fun `incompatible Boolean conditions should still refute the current branch`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const b Bool) (declare-const x Real) (assert (or b (= (* x x) (- 1))))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            val contradictory = invocation.check(PolynomialCondition(bools = mapOf(0 to false)))
            val retracted = invocation.check()

            assertIs<QfNraResult.Unsatisfiable>(contradictory)
            assertTrue(assertIs<QfNraResult.Satisfiable>(retracted).assignment.boolValue(0))
        }
    }

    @Test
    fun `numeric bounds should require a fresh check after a retained witness`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (>= x 0)) (assert (= (* x x) 1))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            val result = invocation.check(PolynomialCondition(lower = mapOf(0 to BigFraction.ofLong(2))))

            assertIs<QfNraResult.Unsatisfiable>(result)
        }
    }

    @Test
    fun `retained witness compatibility should respect the final native work poll`() {
        val source = SmtLibPolynomial.parse("(declare-const b Bool) (assert b)")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val initial = service.invocation().use { it.check().stats.workSpent }

        service.invocation(QfNraParams(maximumWork = initial + 3)).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            val result = invocation.check(PolynomialCondition(bools = mapOf(0 to true)))

            assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
            assertEquals(initial + 3, invocation.stats.workSpent)
        }
    }

    @Test
    fun `cancelled retained witness check should leave a later query available`() {
        val source = SmtLibPolynomial.parse("(declare-const b Bool) (assert b)")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            val cancelled = invocation.check(cancellation = Cancellation { true })
            val resumed = invocation.check()

            assertEquals(QfNraReason.Interrupted, assertIs<QfNraResult.Unknown>(cancelled).reason)
            assertTrue(assertIs<QfNraResult.Satisfiable>(resumed).assignment.boolValue(0))
        }
    }

    @Test
    fun `Boolean preferences should share the invocation meter`() {
        val source = SmtLibPolynomial.parse("(declare-const b Bool) (assert b)")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        var charges = 0L
        val meter = object : Cancellation, WorkMeter {
            override fun isCancelled() = false
            override fun workMeter(): WorkMeter = this
            override fun charge(units: Long) { charges += units }
        }
        val invocation = service.invocation(QfNraParams(cancellation = meter))
        assertIs<QfNraResult.Satisfiable>(invocation.check())

        val preferred = invocation.preferredBool(0, Cancellation.Never)
        invocation.close()

        assertEquals(true, preferred)
        assertEquals(invocation.stats.workSpent, charges)
    }


    @Test
    fun `cancellation at retained witness publication should withhold the result`() {
        val source = SmtLibPolynomial.parse("(declare-const b Bool) (assert b)")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        var charges = 0L
        var stopAt = Long.MAX_VALUE
        val token = object : Cancellation, WorkMeter {
            override fun isCancelled() = charges >= stopAt
            override fun workMeter(): WorkMeter = this
            override fun charge(units: Long) { charges += units }
        }

        service.invocation(QfNraParams(cancellation = token)).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            stopAt = charges + 3
            val result = invocation.check(PolynomialCondition(bools = mapOf(0 to true)))

            assertEquals(QfNraReason.Interrupted, assertIs<QfNraResult.Unknown>(result).reason)
            assertEquals(charges, invocation.stats.workSpent)
        }
    }


    @Test
    fun `closed invocation should discard Boolean witness preferences`() {
        val source = SmtLibPolynomial.parse("(declare-const b Bool) (assert b)")
        val invocation = PolynomialTheoryService(source.formula, source.boolCount, source.realCount).invocation()
        assertIs<QfNraResult.Satisfiable>(invocation.check())

        invocation.close()

        assertNull(invocation.preferredBool(0, Cancellation.Never))
    }
}
