package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClauseSearchComponentTest {

    @Test
    fun `a satisfied clause should imply again after its satisfying literal is retracted`() {
        val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2, 4))))))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(2)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `relocated watches should imply after a satisfying branch is retracted`() {
        val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2, 4, 6))))))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        session.popTo(0)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(7)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))

        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `a satisfied high identifier should not mask a unit consequence in a sibling`() {
        for (variable in listOf(1, 65_537, (1 shl 30) - 1)) {
            val literal = variable shl 1
            val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, literal))))))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(literal)))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
            session.popTo(0)

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(literal xor 1)))

            assertEquals(true, session.boolValue(0))
        }
    }

    @Test
    fun `widely separated watched identifiers should retain their implications`() {
        for (variable in listOf(1, 257, 65_537, (1 shl 30) - 1)) {
            val literal = variable shl 1
            val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, literal))))))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))

            assertEquals(true, session.boolValue(variable))
        }
    }

    @Test
    fun `widely separated watches should propagate after a reversed sibling assignment`() {
        for (variable in listOf(1, 257, 65_537, (1 shl 30) - 1)) {
            val literal = variable shl 1
            val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, literal))))))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
            session.popTo(0)

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(literal xor 1)))

            assertEquals(true, session.boolValue(0))
        }
    }

    @Test
    fun `unit clause should imply its literal at the root`() {
        val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0))))))

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `clause should imply its last literal once the others are falsified`() {
        val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2, 4))))))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `clause should conflict when propagation falsifies every literal`() {
        val session = SearchSession(
            listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3))))),
        )
        assertIs<ComponentResult.Consistent>(session.initialize())

        val result = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(1)))

        assertEquals(setOf(0, 3), result.explanation?.literals?.toSet())
    }

    @Test
    fun `clause should imply again after its literals are refalsified in another order`() {
        val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2, 4))))))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `implied literal should propagate the clauses it falsifies in turn`() {
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
    fun `solver should refute a clause set with no model`() {
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
