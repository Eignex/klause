package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CardinalitySearchComponentTest {

    private fun sessionOf(vararg cardinalities: Cardinality) =
        SearchSession(listOf(CardinalitySearchComponent(cardinalities.toList())))

    @Test
    fun `a lower bound equal to the literal count fixes every literal at the root`() {
        val session = sessionOf(Cardinality(intArrayOf(0, 2), min = 2, max = 2))

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(true, session.boolValue(0))
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `an upper bound of zero falsifies every literal at the root`() {
        val session = sessionOf(Cardinality(intArrayOf(0, 2), min = 0, max = 0))

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(false, session.boolValue(0))
        assertEquals(false, session.boolValue(1))
    }

    @Test
    fun `at most one falsifies the remaining literals once one is true`() {
        val session = sessionOf(Cardinality.atMostOne(intArrayOf(0, 2, 4)))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

        assertEquals(false, session.boolValue(1))
        assertEquals(false, session.boolValue(2))
    }

    @Test
    fun `a lower bound implies its last literal once the others are falsified`() {
        val session = sessionOf(Cardinality(intArrayOf(0, 2, 4), min = 1, max = 3))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `a literal one cardinality fixes propagates the cardinalities it wakes in turn`() {
        val session = sessionOf(
            Cardinality.atMostOne(intArrayOf(0, 2)),
            Cardinality(intArrayOf(2, 4), min = 1, max = 2),
        )
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

        assertEquals(false, session.boolValue(1), "the at-most-one falsifies its other literal")
        assertEquals(true, session.boolValue(2), "which leaves the lower bound one literal to satisfy it")
    }

    @Test
    fun `a refuted lower bound explains itself with the literals that were falsified`() {
        val session = sessionOf(
            Cardinality.atMostOne(intArrayOf(0, 2, 4)),
            Cardinality(intArrayOf(2, 4), min = 1, max = 2),
        )
        assertIs<ComponentResult.Consistent>(session.initialize())

        val result = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(0)))

        assertEquals(setOf(2, 4), result.explanation?.literals?.toSet())
    }

    @Test
    fun `a cardinality implies again after its literals are refalsified in another order`() {
        val session = sessionOf(Cardinality(intArrayOf(0, 2, 4), min = 1, max = 3))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `a negated literal counts its own truth rather than its variable's`() {
        val session = sessionOf(Cardinality(intArrayOf(1, 3), min = 2, max = 2))

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(false, session.boolValue(0))
        assertEquals(false, session.boolValue(1))
    }

    @Test
    fun `solver refutes bounds no count over the same literals can meet`() {
        val session = sessionOf(
            Cardinality(intArrayOf(0, 2, 4), min = 2, max = 3),
            Cardinality(intArrayOf(0, 2, 4), min = 0, max = 1),
        )
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<BooleanSearchResult.Exhausted>(session.solveBoolean(3))
    }
}
