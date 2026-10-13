@file:Suppress("DEPRECATION")

package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceBoundAtom as SharedSourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm as SharedSourceBoundTerm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class SourceBoundAtomTest {
    @Test
    fun `legacy constructors preserve source payload access`() {
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
        val terms = listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE))

        val split = assertNotNull(SourceBoundAtom.integerSplit(session, terms, BigFraction.ZERO))
        val bound = assertIs<SourceBoundAtom>(split.positive.payload)

        assertEquals(terms, bound.terms)
        assertEquals(BigFraction.ZERO, bound.threshold)
        assertEquals(true, bound.upper)
        assertEquals(false, bound.strict)
        assertEquals(bound.sourceBound, bound)
        assertEquals(bound.hashCode(), bound.sourceBound.hashCode())
    }

    @Test
    fun `legacy and neutral constructors reuse the same atom in either registration order`() {
        for (legacyFirst in listOf(false, true)) {
            for (integer in listOf(false, true)) {
                val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
                val source = if (integer) SearchIntValue(0) else SearchRealValue(0)
                fun legacy() = assertNotNull(if (integer) {
                    SourceBoundAtom.integerSplit(session, listOf(SourceBoundTerm(source, BigFraction.ONE)), BigFraction.ZERO)
                } else {
                    SourceBoundAtom.rationalSplit(session, listOf(SourceBoundTerm(source, BigFraction.ONE)), BigFraction.ZERO, true)
                })
                fun neutral() = assertNotNull(if (integer) {
                    SharedSourceBoundAtom.integerSplit(session, listOf(SharedSourceBoundTerm(source, BigFraction.ONE)), BigFraction.ZERO)
                } else {
                    SharedSourceBoundAtom.rationalSplit(session, listOf(SharedSourceBoundTerm(source, BigFraction.ONE)), BigFraction.ZERO, true)
                })

                val first = if (legacyFirst) legacy() else neutral()
                val second = if (legacyFirst) neutral() else legacy()

                assertSame(first.positive, second.positive)
                assertSame(first.negative, second.negative)
            }
        }
    }
}
