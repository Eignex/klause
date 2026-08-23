package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchSessionTest {

    @Test
    fun `shared session retracts every component to the same decision level`() {
        val first = RecordingComponent()
        val second = RecordingComponent()
        val session = SearchSession(listOf(first, second))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(4)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(7)))
        session.popTo(1)

        assertEquals(1, session.decisionLevel)
        assertEquals(listOf(1), first.retractions)
        assertEquals(listOf(1), second.retractions)
    }

    @Test
    fun `indeterminate component check never becomes infeasible`() {
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Indeterminate
            }),
        )

        assertIs<ComponentCheck.Indeterminate>(session.check())
    }

    @Test
    fun `components observe shared Boolean decisions without finite-domain state`() {
        var observed: Boolean? = null
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    observed = context.boolValue(0)
                    return ComponentResult.Consistent
                }
            }),
        )

        session.push(SearchDecision.Bool(0))

        assertEquals(true, observed)
    }

    @Test
    fun `component implication reaches peers and retracts with its decision level`() {
        var observed: Boolean? = null
        val source = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                context.imply(2, SearchExplanation(intArrayOf(decision.let { (it as SearchDecision.Bool).literal })))
        }
        val peer = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                if ((decision as SearchDecision.Bool).literal == 2) observed = context.boolValue(1)
                return ComponentResult.Consistent
            }
        }
        val session = SearchSession(listOf(source, peer))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        assertEquals(true, observed)
        session.popTo(0)

        assertEquals(null, session.boolValue(1))
    }

    @Test
    fun `component integer bounds are shared semantically and retract with the trail`() {
        var observed: Long? = null
        val source = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtMost(7, 4))
        }
        val peer = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                if (decision is SearchDecision.IntAtMost) observed = context.intUpperBound(7)
                return ComponentResult.Consistent
            }
        }
        val session = SearchSession(listOf(source, peer))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

        assertEquals(4L, observed)
        assertEquals(4L, session.intUpperBound(7))
        session.popTo(0)
        assertEquals(null, session.intUpperBound(7))
    }

    @Test
    fun `native component import reaches peers without reasserting the source`() {
        var sourceAssertions = 0
        var peerAssertions = 0
        val source = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                sourceAssertions++
                return ComponentResult.Consistent
            }
        }
        val peer = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                peerAssertions++
                return ComponentResult.Consistent
            }
        }
        val session = SearchSession(listOf(source, peer))

        assertIs<ComponentResult.Consistent>(session.publishFrom(source, SearchDecision.Bool(0)))
        assertIs<ComponentResult.Consistent>(session.propagate())

        assertEquals(0, sourceAssertions)
        assertEquals(1, peerAssertions)
    }

    @Test
    fun `source learned clause reaches peers without reasserting its native owner`() {
        var sourceAssertions = 0
        var peerAssertions = 0
        val source = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                sourceAssertions++
                return ComponentResult.Consistent
            }
        }
        val peer = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                peerAssertions++
                return ComponentResult.Consistent
            }
        }
        val session = SearchSession(listOf(source, peer))

        session.learnFrom(source, SearchExplanation(intArrayOf(0)))
        assertIs<ComponentResult.Consistent>(session.propagate())

        assertEquals(true, session.boolValue(0))
        assertEquals(0, sourceAssertions)
        assertEquals(1, peerAssertions)
    }

    @Test
    fun `session assembles component-owned model values`() {
        val component = object : SearchComponent {
            override fun contributeModel(model: SearchModel, context: SearchContext) {
                model.put(this, "model")
            }
        }
        val session = SearchSession(listOf(component))

        val model = session.model()

        assertEquals("model", model.valueOf<String>(component))
    }

    @Test
    fun `session assembles assigned Boolean values under source keys`() {
        val session = SearchSession(emptyList())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

        assertEquals(true, session.model().valueOf<Boolean>(SearchBoolValue(0)))
    }

    @Test
    fun `session accepts a theory-defined model key`() {
        data class ArrayModelValue(val symbol: String) : SearchValueKey
        val key = ArrayModelValue("a")
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun contributeModel(model: SearchModel, context: SearchContext) {
                    model.put(key, "store")
                }
            }),
        )

        assertEquals("store", session.model().valueOf<String>(key))
    }

    @Test
    fun `Boolean driver backtracks a component-refuted clause-consistent leaf`() {
        val refuter = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = if (context.boolValue(0) == false) {
                ComponentCheck.Infeasible()
            } else {
                ComponentCheck.Feasible
            }
        }
        val session = SearchSession(listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2)))), refuter))

        assertIs<ComponentResult.Consistent>(session.initialize())
        val result = session.solveBoolean(2)

        assertIs<BooleanSearchResult.Satisfied>(result)
        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `Boolean driver preserves an indeterminate leaf as unknown`() {
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Indeterminate
            }),
        )

        assertIs<BooleanSearchResult.Indeterminate>(session.solveBoolean(1))
    }

    @Test
    fun `Boolean driver retains a sound leaf explanation as a learned clause`() {
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = if (context.boolValue(0) == false) {
                    ComponentCheck.Infeasible(SearchExplanation(intArrayOf(0)))
                } else {
                    ComponentCheck.Feasible
                }
            }),
        )

        assertIs<BooleanSearchResult.Satisfied>(session.solveBoolean(1))

        assertEquals(1, session.learnedClauseCount)
        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `generic driver backtracks component branches through the shared trail`() {
        val brancher = object : SearchBrancher {
            override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                if (context.intLowerBound(0) == null) {
                    listOf(SearchDecision.IntEqual(0, 0), SearchDecision.IntEqual(0, 1))
                } else {
                    null
                }
        }
        val refuter = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = if (context.intLowerBound(0) == 0L) {
                ComponentCheck.Infeasible()
            } else {
                ComponentCheck.Feasible
            }
        }
        val session = SearchSession(listOf(brancher, refuter))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<SearchResult.Satisfied>(session.solve(0))

        assertEquals(1L, session.intLowerBound(0))
    }

    @Test
    fun `generic driver dispatches a typed theory branch through the shared trail`() {
        data class Choose(val symbol: String) : SearchTheoryDecision
        var chosen: String? = null
        val brancher = object : SearchBrancher {
            private var asserted = false

            override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                if (asserted) null else listOf(SearchDecision.Theory(Choose("array-a")))

            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                chosen = (decision as? SearchDecision.Theory)?.decision.let { it as? Choose }?.symbol
                asserted = chosen != null
                return ComponentResult.Consistent
            }

            override fun retract(decisionLevel: Int) {
                if (decisionLevel == 0) {
                    asserted = false
                    chosen = null
                }
            }
        }
        val session = SearchSession(listOf(brancher))

        assertIs<SearchResult.Satisfied>(session.solve(0))

        assertEquals("array-a", chosen)
        assertEquals(1, session.decisionLevel)
    }

    @Test
    fun `restart retracts shared state before notifying components`() {
        var restartedAt = -1
        val component = object : SearchComponent {
            override fun onRestart(context: SearchContext) {
                restartedAt = context.decisionLevel
            }
        }
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        session.restart()

        assertEquals(0, restartedAt)
        assertEquals(null, session.boolValue(0))
    }

    @Test
    fun `generic driver restarts at a decision boundary and retains learned clauses`() {
        var restarts = 0
        val refuter = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = when (context.boolValue(0)) {
                false -> ComponentCheck.Infeasible(SearchExplanation(intArrayOf(0)))
                true -> ComponentCheck.Feasible
                null -> ComponentCheck.Indeterminate
            }

            override fun onRestart(context: SearchContext) {
                restarts++
            }
        }
        val session = SearchSession(listOf(refuter))

        val result = session.solve(
            1,
            SearchSolveParams(maxDecisions = 3, restart = SearchRestart.Every(1)),
        )

        assertIs<SearchResult.Satisfied>(result)
        assertEquals(1, restarts)
        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `generic driver returns unknown when its decision allowance is spent`() {
        val session = SearchSession(emptyList())

        assertIs<SearchResult.Indeterminate>(session.solve(2, SearchSolveParams(maxDecisions = 1)))
    }

    private class RecordingComponent : SearchComponent {
        val retractions = ArrayList<Int>()

        override fun retract(decisionLevel: Int) {
            retractions.add(decisionLevel)
        }
    }
}
