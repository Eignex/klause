package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SmtRealPredicatesTest {
    @Test
    fun `scaled and reversed comparisons should share a literal across tagged coordinates`() {
        val predicates = SmtRealPredicates(Cancellation.Never)
        val term = RealComb(
            mapOf(2 to BigFraction.ofLong(3), 0 to BigFraction.ofLong(2)),
            mapOf(0 to BigFraction.ofLong(5)),
            BigFraction.ofLong(-7),
        )
        val first = predicates.literal(term, LinearOp.LE, strict = false) { Lit.make(4, true) }

        for (scale in listOf(BigFraction.ofLong(6), BigFraction.ofLong(-2).reciprocal())) {
            val relation = if (scale.signum() < 0) LinearOp.GE else LinearOp.LE
            val scaled = term.scaled(scale)
            val reordered = RealComb(
                mapOf(0 to scaled.intCoeffs.getValue(0), 2 to scaled.intCoeffs.getValue(2)),
                scaled.realCoeffs,
                scaled.constant,
            )
            val repeated = predicates.literal(reordered, relation, strict = false) { error("duplicate definition") }

            assertEquals(first, repeated)
        }
    }

    @Test
    fun `complementary inequalities should share opposite literals with distinct boundaries`() {
        val term = RealComb(emptyMap(), mapOf(0 to BigFraction.ONE), BigFraction.MINUS_ONE)
        for (relation in listOf(LinearOp.LE, LinearOp.GE)) {
            val opposite = if (relation == LinearOp.LE) LinearOp.GE else LinearOp.LE
            for (strict in listOf(false, true)) {
                val predicates = SmtRealPredicates(Cancellation.Never)
                val first = predicates.literal(term, relation, strict) { Lit.make(4, true) }

                val complement = predicates.literal(term, opposite, !strict) { error("duplicate complement") }
                val reversed = predicates.literal(term.scaled(BigFraction.MINUS_ONE), opposite, strict) {
                    error("duplicate reversal")
                }
                val otherBoundary = predicates.literal(term, relation, !strict) { Lit.make(7, true) }

                assertEquals(Lit.negate(first), complement)
                assertEquals(first, reversed)
                assertNotEquals(first, otherBoundary)
            }
        }
    }

    @Test
    fun `a full predicate table should still reuse scaled and complementary names`() {
        val predicates = SmtRealPredicates(Cancellation.Never, maxEntries = 2)
        val term = RealComb(emptyMap(), mapOf(0 to BigFraction.ofLong(-2)), BigFraction.ZERO)
        val first = predicates.literal(term, LinearOp.GE, strict = true) { Lit.make(4, true) }

        val scaled = predicates.literal(term.scaled(BigFraction.ofLong(3)), LinearOp.GE, strict = true) {
            error("duplicate scaled definition")
        }
        val complement = predicates.literal(term, LinearOp.LE, strict = false) { error("duplicate complement") }

        assertEquals(first, scaled)
        assertEquals(Lit.negate(first), complement)
    }

    @Test
    fun `interruption during predicate normalization should not publish a definition`() {
        var polls = 0
        val predicates = SmtRealPredicates(Cancellation { ++polls >= 3 })
        val term = RealComb(emptyMap(), mapOf(0 to BigFraction.ONE, 1 to BigFraction.MINUS_ONE), BigFraction.ZERO)
        var definitions = 0

        assertFailsWith<PolynomialSourceInterrupted> {
            predicates.literal(term, LinearOp.LE, strict = false) { definitions++ }
        }

        assertEquals(0, definitions)
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
