package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun `complete check clears a resolver from an earlier conflict`() {
        val resolver = object : SearchConflictResolver {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                ComponentResult.Conflict()

            override fun resolveConflict(context: SearchContext): SearchConflictResolution =
                SearchConflictResolution.Chronological
        }
        val session = SearchSession(listOf(resolver))

        assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(0)))
        assertTrue(session.hasNativeConflictResolver)
        session.popTo(0)
        assertIs<ComponentCheck.Feasible>(session.check())

        assertEquals(false, session.hasNativeConflictResolver)
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
    fun `shared analysis resolves a nonasserting theory conflict to first UIP`() {
        val theory = object : SearchConflictResolver {
            override val prefersNativeConflictAnalysis: Boolean get() = false

            override fun propagate(context: SearchContext): ComponentResult = if (
                context.boolValue(3) == true && context.boolValue(4) == true
            ) {
                ComponentResult.Conflict(SearchExplanation(intArrayOf(3, 7, 9)))
            } else {
                ComponentResult.Consistent
            }

            override fun resolveConflict(context: SearchContext): SearchConflictResolution =
                error("mixed conflicts use shared analysis")
        }
        val session = SearchSession(
            listOf(
                ClauseSearchComponent(
                    listOf(
                        Clause(intArrayOf(0, 2)),
                        Clause(intArrayOf(4, 6)),
                        Clause(intArrayOf(4, 8)),
                    ),
                ),
                theory,
            ),
        )
        var learned: SearchLearnedConflict? = null
        val run = session.openRun(
            numBoolVars = 5,
            observer = object : SearchRunObserver {
                override fun onLearnedConflict(conflict: SearchLearnedConflict) {
                    learned = conflict
                }
            },
        )

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(1, session.learnedClauseCount)
        assertEquals(1, learned?.decisionLevel)
        assertTrue(learned?.guardLiterals?.contentEquals(intArrayOf(3, 4)) == true)
        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `shared analysis identifies a root explanation as exhausted`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0)))

        assertIs<ComponentResult.Consistent>(session.propagate())

        assertEquals(
            SearchConflictResolution.Exhausted,
            session.explainedConflict(SearchExplanation(intArrayOf(1))),
        )
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
    fun `node policy learns at its asserting backjump level`() {
        var appliedAt: Int? = null
        var pending = true
        var learnedBackjumps = 0
        val policy = object : SearchNodePolicy {
            override fun beforeBranch(context: SearchContext): SearchNodeDisposition = if (
                pending && context.decisionLevel == 2
            ) {
                SearchNodeDisposition.Backjump(object : SearchLearnedConflict {
                    override val decisionLevel: Int = 1
                    override val lbd: Int = 1
                    override val guardLiterals: IntArray = intArrayOf(0)
                    override val decisionLevels: IntArray = intArrayOf(1)

                    override fun apply(session: SearchSession): SearchLearnedConflictResult {
                        appliedAt = session.decisionLevel
                        pending = false
                        return SearchLearnedConflictResult.Resume
                    }
                })
            } else {
                SearchNodeDisposition.Expand
            }
        }
        val observer = object : SearchRunObserver {
            override fun onLearnedNodeBackjump() {
                learnedBackjumps++
            }
        }
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 2,
            nodePolicy = policy,
            observer = observer,
        )

        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(1, appliedAt)
        assertEquals(1, learnedBackjumps)
    }

    @Test
    fun `learned conflicts feed restart quality and observer metadata before backjumping`() {
        var recorded: Pair<Int, Int>? = null
        var observedLevels: IntArray? = null
        val restart = object : SearchRestartPolicy {
            override fun recordConflict(lbd: Int, trailSize: Int) {
                recorded = lbd to trailSize
            }

            override fun shouldRestart(decisionsThisRun: Long): Boolean = false
        }
        var pending = true
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 2,
            params = SearchSolveParams(restart = restart),
            nodePolicy = object : SearchNodePolicy {
                override fun beforeBranch(context: SearchContext): SearchNodeDisposition = if (
                    pending && context.decisionLevel == 2
                ) {
                    SearchNodeDisposition.Backjump(object : SearchLearnedConflict {
                        override val decisionLevel: Int = 1
                        override val lbd: Int = 7
                        override val guardLiterals: IntArray = intArrayOf(0)
                        override val decisionLevels: IntArray = intArrayOf(1, 4)

                        override fun apply(session: SearchSession): SearchLearnedConflictResult {
                            pending = false
                            return SearchLearnedConflictResult.Resume
                        }
                    })
                } else {
                    SearchNodeDisposition.Expand
                }
            },
            observer = object : SearchRunObserver {
                override fun onLearnedConflict(conflict: SearchLearnedConflict) {
                    observedLevels = conflict.decisionLevels
                }
            },
        )

        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(7 to 2, recorded)
        assertTrue(observedLevels!!.contentEquals(intArrayOf(1, 4)))
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

    @Test
    fun `model policy continues through a non-reportable model`() {
        var seen = 0
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 1,
            modelPolicy = object : SearchModelPolicy {
                override fun onModel(model: AssembledSearchModel, context: SearchContext): SearchModelDisposition =
                    if (++seen == 1) SearchModelDisposition.Continue else SearchModelDisposition.Surface
            },
        )

        val result = assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(2, seen)
        assertEquals(true, result.model.valueOf<Boolean>(SearchBoolValue(0)))
    }

    @Test
    fun `node policy prunes through the shared frame stack`() {
        var calls = 0
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 1,
            nodePolicy = object : SearchNodePolicy {
                override fun beforeBranch(context: SearchContext): SearchNodeDisposition =
                    if (++calls >= 2) SearchNodeDisposition.Prune else SearchNodeDisposition.Expand
            },
        )

        assertIs<SearchRunEvent.Exhausted>(run.next())
        assertEquals(3, calls)
    }

    @Test
    fun `learned clause implies its last unfalsified literal`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0, 2, 4)))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `learned clause conflicts when propagation falsifies every literal`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.learn(SearchExplanation(intArrayOf(0, 3)))

        val result = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(1)))

        assertEquals(setOf(0, 3), result.explanation?.literals?.toSet())
    }

    @Test
    fun `learned clause implies again after its watches are refalsified in another order`() {
        val session = SearchSession(emptyList())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        session.learn(SearchExplanation(intArrayOf(0, 2, 4)))
        assertIs<ComponentResult.Consistent>(session.propagate())
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `learned unit clause is reimplied after the level that learned it is retracted`() {
        val session = SearchSession(emptyList())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))
        session.learn(SearchExplanation(intArrayOf(0)))
        assertIs<ComponentResult.Consistent>(session.propagate())
        session.popTo(0)
        assertEquals(null, session.boolValue(0))

        assertIs<ComponentResult.Consistent>(session.propagate())

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `permuted explanation does not enter the database twice`() {
        val session = SearchSession(emptyList())

        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.learn(SearchExplanation(intArrayOf(2, 0)))

        assertEquals(1, session.learnedClauseCount)
    }

    @Test
    fun `restart drops the clauses over the learned database cap`() {
        val session = SearchSession(
            emptyList(),
            learnedDb = SearchLearnedDbParams(maxClauses = 1, glueLbd = 0),
        )
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.learn(SearchExplanation(intArrayOf(4, 6)))
        assertIs<ComponentResult.Consistent>(session.propagate())

        assertIs<ComponentResult.Consistent>(session.restart())

        assertEquals(1, session.learnedClauseCount)
    }

    @Test
    fun `restart retains glue clauses over the learned database cap`() {
        val session = SearchSession(
            emptyList(),
            learnedDb = SearchLearnedDbParams(maxClauses = 1, glueLbd = 1),
        )
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.learn(SearchExplanation(intArrayOf(4, 6)))
        assertIs<ComponentResult.Consistent>(session.propagate())

        assertIs<ComponentResult.Consistent>(session.restart())

        assertEquals(2, session.learnedClauseCount)
    }

    @Test
    fun `a clause retained through a reduction still propagates`() {
        val session = SearchSession(
            emptyList(),
            learnedDb = SearchLearnedDbParams(maxClauses = 1, glueLbd = 0),
        )
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        assertIs<ComponentResult.Consistent>(session.propagate())
        assertIs<ComponentResult.Consistent>(session.restart())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))

        assertEquals(true, session.boolValue(1))
    }

    private class RecordingComponent : SearchComponent {
        val retractions = ArrayList<Int>()

        override fun retract(decisionLevel: Int) {
            retractions.add(decisionLevel)
        }
    }
}
