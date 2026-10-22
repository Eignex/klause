package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmtRealPredicatesTest {
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
