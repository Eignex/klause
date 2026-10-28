package com.eignex.klause.solver.pipeline

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.solver.search.explainAtoms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearRefutationSupportTest {
    @Test
    fun `stopped support selection should not publish a partial reason`() {
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
        session.initialize()
        val x = assertNotNull(SourceBoundAtom.integerSplit(session,
            listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)), BigFraction.ZERO))

        val selected = linearRefutationSupport(SourceCondition(), emptyList(), SourceCondition(), x.negative) {
            false
        }

        assertNull(selected)
    }

    @Test
    fun `unit support should omit unrelated decisions and retain stronger established endpoints`() {
        for (endpoint in listOf(BigFraction.ZERO, BigFraction.MINUS_ONE, BigFraction.ONE)) {
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(0))
            session.initialize()
            val x = assertNotNull(SourceBoundAtom.integerSplit(session,
                listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)), endpoint))
            val y = assertNotNull(SourceBoundAtom.integerSplit(session,
                listOf(SourceBoundTerm(SearchIntValue(1), BigFraction.ONE)), BigFraction.ZERO))
            val z = assertNotNull(SourceBoundAtom.integerSplit(session,
                listOf(SourceBoundTerm(SearchIntValue(2), BigFraction.ONE)), BigFraction.ZERO))
            val assertions = listOf(SearchDecision.Theory(z.positive), SearchDecision.Theory(x.positive))
            assertions.forEach { session.push(it) }
            val support = SourceCondition(upper = mapOf(
                SourceCoordinate(SourceCoordinate.Kind.Integer, 0) to BigFraction.ZERO,
                SourceCoordinate(SourceCoordinate.Kind.Integer, 1) to BigFraction.ZERO))

            val selected = linearRefutationSupport(SourceCondition(), assertions, support, y.negative)

            if (endpoint > BigFraction.ZERO) assertNull(selected) else {
                assertEquals(listOf(assertions.last()), selected)
                val reason = assertNotNull(session.explainAtoms(SearchAtomPremise.All(
                    assertNotNull(selected).map { SearchAtomPremise.Asserted(it) }), SearchDecision.Theory(y.negative)))
                assertEquals(setOf(y.negative.literal, x.negative.literal), reason.literals.toSet())
            }
        }
    }
}
