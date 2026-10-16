package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SmtRealPredicatesTest {
    @Test
    fun `positive scalar equivalents should reuse one predicate name`() {
        val predicates = SmtRealPredicates(Cancellation.Never)
        val first = RealComb(mapOf(0 to BigFraction.ofLong(2)), mapOf(0 to BigFraction.ofLong(4)),
            BigFraction.ofLong(6))
        val second = RealComb(mapOf(0 to BigFraction.ofLong(3)), mapOf(0 to BigFraction.ofLong(6)),
            BigFraction.ofLong(9))
        var definitions = 0

        val firstName = predicates.literal(first, LinearOp.LE, false) { Lit.make(definitions++, true) }
        val secondName = predicates.literal(second, LinearOp.LE, false) { Lit.make(definitions++, true) }

        assertEquals(firstName, secondName)
        assertEquals(1, definitions)
    }

    @Test
    fun `reversing both coefficients and direction should preserve the predicate`() {
        val predicates = SmtRealPredicates(Cancellation.Never)
        val first = RealComb(emptyMap(), mapOf(0 to BigFraction.ofLong(2)), BigFraction.ofLong(4))
        val second = RealComb(emptyMap(), mapOf(0 to BigFraction.ofLong(-3)), BigFraction.ofLong(-6))
        var definitions = 0

        val firstName = predicates.literal(first, LinearOp.LE, false) { Lit.make(definitions++, true) }
        val secondName = predicates.literal(second, LinearOp.GE, false) { Lit.make(definitions++, true) }

        assertEquals(firstName, secondName)
        assertEquals(1, definitions)
    }

    @Test
    fun `exact complements should reuse opposite literals in either creation order`() {
        for ((relation, strict) in listOf(LinearOp.LE to false, LinearOp.GE to false,
            LinearOp.LE to true, LinearOp.GE to true)) {
            val predicates = SmtRealPredicates(Cancellation.Never)
            val term = RealComb(emptyMap(), mapOf(0 to BigFraction.ONE), BigFraction.ZERO)
            val complement = if (relation == LinearOp.LE) LinearOp.GE else LinearOp.LE
            var definitions = 0

            val first = predicates.literal(term, relation, strict) { Lit.make(definitions++, true) }
            val second = predicates.literal(term, complement, !strict) { Lit.make(definitions++, true) }

            assertEquals(Lit.negate(first), second)
            assertEquals(1, definitions)
        }
    }

    @Test
    fun `open and closed comparisons in the same direction should stay distinct`() {
        for (coefficient in listOf(BigFraction.ONE, BigFraction.MINUS_ONE)) {
            val predicates = SmtRealPredicates(Cancellation.Never)
            val term = RealComb(emptyMap(), mapOf(0 to coefficient), BigFraction.ZERO)
            var definitions = 0

            val closed = predicates.literal(term, LinearOp.LE, false) { Lit.make(definitions++, true) }
            val open = predicates.literal(term, LinearOp.LE, true) { Lit.make(definitions++, true) }

            assertNotEquals(closed, open)
            assertEquals(2, definitions)
        }
    }

    @Test
    fun `coefficient insertion order should not choose a different normalization`() {
        val predicates = SmtRealPredicates(Cancellation.Never)
        val first = RealComb(emptyMap(), linkedMapOf(1 to BigFraction.ofLong(6), 0 to BigFraction.ofLong(3)),
            BigFraction.ofLong(9))
        val second = RealComb(emptyMap(), linkedMapOf(0 to BigFraction.ofLong(2), 1 to BigFraction.ofLong(4)),
            BigFraction.ofLong(6))
        var definitions = 0

        val firstName = predicates.literal(first, LinearOp.LE, false) { Lit.make(definitions++, true) }
        val secondName = predicates.literal(second, LinearOp.LE, false) { Lit.make(definitions++, true) }

        assertEquals(firstName, secondName)
        assertEquals(1, definitions)
    }

    @Test
    fun `oversized normalization should retain exact lookup without scaled aliases`() {
        val tiny = BigFraction.of(BIG_ONE, BIG_ONE shl 3000)
        val huge = BigFraction.of(BIG_ONE shl 3000, BIG_ONE)
        val oversized = BigFraction.of(BIG_ONE shl 4097, BIG_ONE)
        for ((first, second) in listOf(
            RealComb(emptyMap(), mapOf(0 to tiny, 1 to huge), BigFraction.ZERO) to
                RealComb(emptyMap(), mapOf(0 to tiny * BigFraction.ofLong(2),
                    1 to huge * BigFraction.ofLong(2)), BigFraction.ZERO),
            RealComb(emptyMap(), mapOf(0 to BigFraction.ONE, 1 to oversized), BigFraction.ZERO) to
                RealComb(emptyMap(), mapOf(0 to BigFraction.ofLong(2),
                    1 to oversized * BigFraction.ofLong(2)), BigFraction.ZERO),
        )) {
            val predicates = SmtRealPredicates(Cancellation.Never)
            var definitions = 0

            val names = listOf(first, second, first).map { term ->
                predicates.literal(term, LinearOp.LE, false) { Lit.make(definitions++, true) }
            }

            assertEquals(listOf(0, 2, 0), names)
            assertEquals(2, definitions)
        }
    }

    @Test
    fun `interrupted normalization should create no predicate and allow a live retry`() {
        var stopped = false
        var armed = true
        val meter = WorkMeter { units -> if (armed && units > 5L) stopped = true }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = stopped
            override fun workMeter(): WorkMeter = meter
        }
        val predicates = SmtRealPredicates(cancellation)
        val term = RealComb(emptyMap(), mapOf(0 to BigFraction.of(BIG_ONE, bigIntOf(3))),
            BigFraction.of(BIG_ONE, bigIntOf(2)))
        var definitions = 0

        assertFailsWith<PolynomialSourceInterrupted> {
            predicates.literal(term, LinearOp.LE, false) { Lit.make(definitions++, true) }
        }

        assertEquals(0, definitions)
        stopped = false
        armed = false
        assertEquals(0, predicates.literal(term, LinearOp.LE, false) { Lit.make(definitions++, true) })
        assertEquals(1, definitions)
    }

    @Test
    fun `a stop during definition creation should withhold its cached alias`() {
        var stopped = false
        val predicates = SmtRealPredicates(Cancellation { stopped })
        val term = RealComb(emptyMap(), mapOf(0 to BigFraction.ONE), BigFraction.ZERO)
        var definitions = 0

        assertFailsWith<PolynomialSourceInterrupted> {
            predicates.literal(term, LinearOp.LE, false) {
                stopped = true
                Lit.make(definitions++, true)
            }
        }
        stopped = false
        val retry = predicates.literal(term, LinearOp.LE, false) { Lit.make(definitions++, true) }
        val repeated = predicates.literal(term, LinearOp.LE, false) { Lit.make(definitions++, true) }

        assertEquals(2, retry)
        assertEquals(retry, repeated)
        assertEquals(2, definitions)
    }

    @Test
    fun `a full predicate table should retain names and still create uncached definitions`() {
        val predicates = SmtRealPredicates(Cancellation.Never, maxEntries = 2)
        val first = RealComb(emptyMap(), mapOf(0 to BigFraction.ONE), BigFraction.ZERO)
        val second = RealComb(emptyMap(), mapOf(1 to BigFraction.ONE), BigFraction.ZERO)
        var next = 0

        val names = listOf(first, second, second, first).map { term ->
            predicates.literal(term, LinearOp.LE, strict = false) { next++ }
        }

        assertEquals(listOf(0, 1, 2, 0), names)
    }

    @Test
    fun `an interrupted predicate lookup should not create a definition`() {
        val predicates = SmtRealPredicates(Cancellation { true })
        val term = RealComb(emptyMap(), mapOf(0 to BigFraction.ONE), BigFraction.ZERO)
        var definitions = 0

        assertFailsWith<PolynomialSourceInterrupted> {
            predicates.literal(term, LinearOp.LE, strict = false) { definitions++ }
        }

        assertEquals(0, definitions)
    }
}
