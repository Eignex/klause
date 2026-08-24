package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClauseSearchComponentTest {

    @Test
    fun `unit clause implies its literal at the root`() {
        val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0))))))

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `clause implies its last literal once the others are falsified`() {
        val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2, 4))))))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `clause conflicts when propagation falsifies every literal`() {
        val session = SearchSession(
            listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3))))),
        )
        assertIs<ComponentResult.Consistent>(session.initialize())

        val result = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(1)))

        assertEquals(setOf(0, 3), result.explanation?.literals?.toSet())
    }

    @Test
    fun `clause implies again after its literals are refalsified in another order`() {
        val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2, 4))))))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `implied literal propagates the clauses it falsifies in turn`() {
        val session = SearchSession(
            listOf(
                ClauseSearchComponent(
                    listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(1, 6))),
                ),
            ),
        )
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(0))
        assertEquals(true, session.boolValue(2))
        assertEquals(true, session.boolValue(3))
    }

    @Test
    fun `solver refutes a clause set with no model`() {
        val session = SearchSession(
            listOf(
                ClauseSearchComponent(
                    listOf(
                        Clause(intArrayOf(0, 2)),
                        Clause(intArrayOf(0, 3)),
                        Clause(intArrayOf(1, 2)),
                        Clause(intArrayOf(1, 3)),
                    ),
                ),
            ),
        )
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<BooleanSearchResult.Exhausted>(session.solveBoolean(2))
    }
}
