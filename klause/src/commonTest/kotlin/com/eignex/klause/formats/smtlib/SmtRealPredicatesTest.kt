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
    fun `opposite comparison directions should share exact predicate identities`() {
        for (strict in listOf(false, true)) for (relation in listOf(LinearOp.LE, LinearOp.GE)) {
            val predicates = SmtRealPredicates(Cancellation.Never)
            val term = RealComb(mapOf(0 to BigFraction.ONE), mapOf(1 to BigFraction.MINUS_ONE), BigFraction.ONE)
            val opposite = term.scaled(BigFraction.MINUS_ONE)
            val direction = if (relation == LinearOp.LE) LinearOp.GE else LinearOp.LE
            val otherSorts = RealComb(mapOf(1 to BigFraction.MINUS_ONE), mapOf(0 to BigFraction.ONE), BigFraction.ONE)
            var next = 0

            val names = listOf(
                predicates.literal(term, relation, strict) { next++ },
                predicates.literal(opposite, direction, strict) { next++ },
                predicates.literal(term, relation, !strict) { next++ },
                predicates.literal(term.plus(term), relation, strict) { next++ },
                predicates.literal(otherSorts, relation, strict) { next++ },
            )
            val cached = predicates.cachedLiteral(opposite, direction, strict)

            assertEquals(listOf(0, 0, 1, 2, 3), names)
            assertEquals(0, cached)
        }
    }

    @Test
    fun `a full extrema table should retain shared results and leave new extrema independent`() {
        val predicates = SmtRealPredicates(Cancellation.Never, maxEntries = 6)
        val first = RealComb(emptyMap(), mapOf(0 to BigFraction.ONE), BigFraction.ZERO)
        val second = RealComb(emptyMap(), mapOf(1 to BigFraction.ONE), BigFraction.ZERO)
        val third = RealComb(emptyMap(), mapOf(2 to BigFraction.ONE), BigFraction.ZERO)
        var next = 3

        val results = listOf(first to second, second to first, first to third, third to first, first to second)
            .map { (left, right) ->
                predicates.extremum(left, right, minimum = true) {
                    RealComb(emptyMap(), mapOf(next++ to BigFraction.ONE), BigFraction.ZERO)
                }.realCoeffs.keys.single()
            }
        var nextLiteral = 0
        val literals = List(2) {
            predicates.literal(first, LinearOp.LE, strict = false) { nextLiteral++ }
        }

        assertEquals(listOf(3, 3, 4, 5, 3), results)
        assertEquals(listOf(0, 1), literals)
    }

    @Test
    fun `an interrupted extrema lookup should not create a result coordinate`() {
        val predicates = SmtRealPredicates(Cancellation { true })
        val first = RealComb(emptyMap(), mapOf(0 to BigFraction.ONE), BigFraction.ZERO)
        val second = RealComb(emptyMap(), mapOf(1 to BigFraction.ONE), BigFraction.ZERO)
        var definitions = 0

        assertFailsWith<PolynomialSourceInterrupted> {
            predicates.extremum(first, second, minimum = true) {
                definitions++
                first
            }
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
