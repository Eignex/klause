package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchValueKey
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceBoundAtomTest {
    @Test
    fun `integer branches exactly partition negative fractional and beyond Long values`() {
        val cases = listOf(
            "3" to "1",
            "-3" to "-2",
            "0" to "0",
            "18446744073709551619" to "9223372036854775809",
            "-18446744073709551619" to "-9223372036854775810",
        )
        for ((numerator, floorText) in cases) {
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
            val value = BigFraction.of(BigInteger.parseString(numerator), BigInteger.TWO)
            val floor = BigInteger.parseString(floorText)
            val atom = assertNotNull(
                SourceBoundAtom.integerSplit(
                    session,
                    listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)),
                    value,
                ),
            )
            val upper = atom.positive.payload as SourceBoundAtom
            val lower = atom.negative.payload as SourceBoundAtom

            assertEquals(BigFraction.of(floor, BigInteger.ONE), upper.threshold)
            assertEquals(BigFraction.of(floor + BigInteger.ONE, BigInteger.ONE), lower.threshold)
            for (offset in -2L..3L) {
                val point = BigFraction.of(floor + BigInteger.fromLong(offset), BigInteger.ONE)
                assertEquals(1, listOf(upper, lower).count { holds(it, mapOf(SearchIntValue(0) to point)) })
            }
        }
    }

    @Test
    fun `rational complements retain strictness at and around a transformed threshold`() {
        for (strict in listOf(false, true)) {
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
            val half = BigFraction.of(BigInteger.ONE, BigInteger.TWO)
            val atom = assertNotNull(
                SourceBoundAtom.rationalSplit(
                    session,
                    listOf(
                        SourceBoundTerm(SearchRealValue(0), half),
                        SourceBoundTerm(SearchIntValue(0), BigFraction.ONE),
                    ),
                    half,
                    strict,
                    constant = BigFraction.ONE,
                ),
            )
            val upper = atom.positive.payload as SourceBoundAtom
            val lower = atom.negative.payload as SourceBoundAtom

            assertEquals(half.negated(), upper.threshold)
            for (real in -3L..1L) {
                val point = mapOf<SearchValueKey, BigFraction>(
                    SearchRealValue(0) to BigFraction.ofLong(real),
                    SearchIntValue(0) to BigFraction.ZERO,
                )
                assertEquals(1, listOf(upper, lower).count { holds(it, point) })
                if (real == -1L) assertEquals(!strict, holds(upper, point))
            }
        }
    }

    @Test
    fun `normalization preserves source meaning after input mutation and reordered duplicate terms`() {
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
        val x = SearchIntValue(0)
        val y = SearchIntValue(1)
        val input = mutableListOf(
            SourceBoundTerm(x, BigFraction.ONE),
            SourceBoundTerm(y, BigFraction.ONE),
            SourceBoundTerm(x, BigFraction.ONE),
        )
        val first = assertNotNull(
            SourceBoundAtom.integerSplit(session, input, BigFraction.ofLong(7), constant = BigFraction.ofLong(3)),
        )
        input.clear()
        val reordered = listOf(SourceBoundTerm(y, BigFraction.ONE), SourceBoundTerm(x, BigFraction.ofLong(2)))
        val second = assertNotNull(SourceBoundAtom.integerSplit(session, reordered, BigFraction.ofLong(4)))

        assertSame(first.positive, second.positive)
        val upper = first.positive.payload as SourceBoundAtom
        for (a in -2L..3L) {
            for (b in -2L..3L) {
                val point = mapOf<SearchValueKey, BigFraction>(x to BigFraction.ofLong(a), y to BigFraction.ofLong(b))
                assertEquals(2 * a + b + 3 <= 7, holds(upper, point))
            }
        }
    }

    @Test
    fun `unsupported lattices source keys and normalization budgets allocate no atoms`() {
        val unsupported = object : SearchValueKey {}
        val cases = listOf(
            listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)) to SourceBoundLimits(),
            listOf(
                SourceBoundTerm(SearchIntValue(0), BigFraction.of(BigInteger.ONE, BigInteger.TWO)),
            ) to SourceBoundLimits(),
            listOf(SourceBoundTerm(unsupported, BigFraction.ONE)) to SourceBoundLimits(),
            listOf(SourceBoundTerm(SearchIntValue(-1), BigFraction.ONE)) to SourceBoundLimits(),
            listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)) to SourceBoundLimits(maxTerms = 0),
            listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ofLong(4))) to SourceBoundLimits(maxBits = 2),
            List(2) { SourceBoundTerm(SearchIntValue(0), BigFraction.ofLong(3)) } to SourceBoundLimits(maxBits = 2),
        )
        for ((terms, limits) in cases) {
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
            assertNull(SourceBoundAtom.integerSplit(session, terms, BigFraction.ZERO, limits = limits))
            val accepted = assertNotNull(SourceBoundAtom.integerSplit(session, emptyList(), BigFraction.ZERO))
            assertEquals(4, accepted.positive.literal)
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `threshold growth and fractional integer constants decline before registration`() {
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
        assertNull(
            SourceBoundAtom.integerSplit(
                session,
                emptyList(),
                BigFraction.ofLong(3),
                limits = SourceBoundLimits(maxBits = 2),
            ),
        )
        assertNull(
            SourceBoundAtom.integerSplit(
                session,
                emptyList(),
                BigFraction.ONE,
                constant = BigFraction.of(BigInteger.ONE, BigInteger.TWO),
            ),
        )
        assertNull(
            SourceBoundAtom.rationalSplit(
                session,
                emptyList(),
                BigFraction.ofLong(3),
                constant = BigFraction.ofLong(-3),
                limits = SourceBoundLimits(maxBits = 2),
            ),
        )
        assertEquals(
            0,
            assertNotNull(SourceBoundAtom.integerSplit(session, emptyList(), BigFraction.ZERO)).positive.literal,
        )
    }

    @Test
    fun `integer normalization cancels nonintegral terms before checking the lattice`() {
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
        val half = BigFraction.of(BigInteger.ONE, BigInteger.TWO)
        val atom = SourceBoundAtom.integerSplit(
            session,
            listOf(SourceBoundTerm(SearchIntValue(0), half), SourceBoundTerm(SearchIntValue(0), half)),
            BigFraction.ZERO,
        )
        assertTrue(assertNotNull(atom).positive.payload is SourceBoundAtom)
    }

    private fun holds(bound: SourceBoundAtom, point: Map<SearchValueKey, BigFraction>): Boolean {
        var activity = BigFraction.ZERO
        for (term in bound.terms) activity += term.coefficient * point.getValue(term.source)
        val comparison = activity.compareTo(bound.threshold)
        return if (bound.upper) {
            comparison < 0 || (comparison == 0 && !bound.strict)
        } else {
            comparison > 0 || (comparison == 0 && !bound.strict)
        }
    }
}
