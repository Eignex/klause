package com.eignex.klause.solver.search

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SearchRunTest {
    @Test
    fun `solution notification should precede model assembly`() {
        var notified = false
        val key = SearchModelKey(Boolean::class)
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
                override fun contributeModel(model: SearchModel, context: SearchContext) {
                    model.contribute(key, notified)
                }
            }),
        )
        val restart = object : SearchRestartPolicy {
            override fun shouldRestart(decisionsThisRun: Long): Boolean = false
            override fun onSolution() {
                notified = true
            }
        }
        session.initialize()

        val model = assertIs<SearchRunEvent.Satisfied>(
            session.openRun(0, SearchSolveParams(restart = restart)).next(),
        ).model

        assertEquals(true, model.valueOf(key))
    }

    @Test
    fun `paused completion should retry the same model before traversing siblings`() {
        for (continuation in listOf(SearchModelContinuation.Chronological, SearchModelContinuation.BlockAtRoot)) {
            var checks = 0
            var observations = 0
            var nodes = 0
            val models = ArrayList<AssembledSearchModel>()
            val cp = CpSearchComponent(
                PropagationSession(Problem(1, 0, emptyArray(), emptyArray()).bake()),
                branching = CpBranching.None,
            )
            val session = SearchSession(
                listOf(
                    cp,
                    object : SearchComponent {
                        override fun check(context: SearchContext): ComponentCheck {
                            checks++
                            return ComponentCheck.Feasible
                        }
                    },
                ),
            )
            session.initialize()
            val run = session.openRun(
                1,
                modelContinuation = continuation,
                observer = object : SearchRunObserver {
                    override fun onModel(model: AssembledSearchModel) {
                        observations++
                    }
                },
                nodePolicy = object : SearchNodePolicy {
                    override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                        nodes++
                        return SearchNodeDisposition.Expand
                    }
                },
                modelPolicy = object : SearchModelPolicy {
                    override fun onModel(model: AssembledSearchModel, context: SearchContext): SearchModelDisposition {
                        models += model
                        return if (models.size < 3) SearchModelDisposition.Pause else SearchModelDisposition.Surface
                    }
                },
            )

            assertIs<SearchRunEvent.Paused>(run.next())
            val pendingLevel = session.decisionLevel
            val pendingNodes = nodes
            assertIs<SearchRunEvent.Paused>(run.next())
            val first = assertIs<SearchRunEvent.Satisfied>(run.next())

            assertSame(models.first(), first.model)
            models.forEach { assertSame(first.model, it) }
            assertEquals(pendingLevel, session.decisionLevel)
            assertEquals(pendingNodes, nodes)
            assertEquals(1, checks)
            assertEquals(1, observations)
            val second = assertIs<SearchRunEvent.Satisfied>(run.next())
            assertEquals(false, first.model.valueOf(SearchBoolValue(0)))
            assertEquals(true, second.model.valueOf(SearchBoolValue(0)))
            assertIs<SearchRunEvent.Exhausted>(run.next())
        }
    }

    @Test
    fun `cancellation should stop pending completion before retrying the policy`() {
        var cancelled = false
        var retries = 0
        val session = SearchSession(emptyList(), cancellation = Cancellation { cancelled })
        session.initialize()
        val run = session.openRun(
            0,
            modelPolicy = object : SearchModelPolicy {
                override fun onModel(model: AssembledSearchModel, context: SearchContext): SearchModelDisposition {
                    retries++
                    return SearchModelDisposition.Pause
                }
            },
        )
        assertIs<SearchRunEvent.Paused>(run.next())

        cancelled = true
        val result = run.next()

        assertIs<SearchRunEvent.Indeterminate>(result)
        assertEquals(SearchStopCause.Cancelled, session.executionControls.stopCause)
        assertEquals(1, retries)
    }

    @Test
    fun `root reset should discard a pending model`() {
        var checks = 0
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck {
                    checks++
                    return ComponentCheck.Feasible
                }
            }),
        )
        session.initialize()
        val run = session.openRun(
            0,
            modelPolicy = object : SearchModelPolicy {
                override fun onModel(model: AssembledSearchModel, context: SearchContext): SearchModelDisposition =
                    SearchModelDisposition.Pause
            },
        )
        assertIs<SearchRunEvent.Paused>(run.next())

        run.reset()
        assertIs<SearchRunEvent.Paused>(run.next())

        assertEquals(2, checks)
    }

    @Test
    fun `a learned backjump should preserve nested conditions`() {
        for (selection in listOf(
            SearchBranchSelection.Default,
            SearchBranchSelection.Priority(
                listOf(SearchBranchKind.Arithmetic, SearchBranchKind.Finite, SearchBranchKind.Boolean),
            ),
        )) {
            val session = SearchSession(
                listOf(object : SearchComponent {
                    override fun check(context: SearchContext): ComponentCheck =
                        if (context.boolValue(0) == true && context.boolValue(1) == false) {
                            ComponentCheck.Infeasible(
                                SearchExplanation(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                            )
                        } else {
                            ComponentCheck.Feasible
                        }
                }),
            )
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            session.push(SearchDecision.Bool(Lit.make(2, true)))
            val run = session.openConditionedRun(3, branchSelection = selection)

            val result = assertIs<SearchRunEvent.Satisfied>(run.next())

            assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
            assertEquals(true, result.model.valueOf(SearchBoolValue(1)))
            assertEquals(true, result.model.valueOf(SearchBoolValue(2)))
        }
    }

    @Test
    fun `restart should preserve the enclosing condition`() {
        for (selection in listOf(
            SearchBranchSelection.Default,
            SearchBranchSelection.Priority(
                listOf(SearchBranchKind.Arithmetic, SearchBranchKind.Finite, SearchBranchKind.Boolean),
            ),
        )) {
            val session = SearchSession(
                listOf(object : SearchComponent {
                    override fun check(context: SearchContext): ComponentCheck = if (context.boolValue(1) == false) {
                        ComponentCheck.Infeasible(SearchExplanation(intArrayOf(Lit.make(1, true))))
                    } else {
                        ComponentCheck.Feasible
                    }
                }),
            )
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            val run = session.openConditionedRun(
                2,
                SearchSolveParams(maxDecisions = 4, restart = SearchRestart.Every(1)),
                branchSelection = selection,
            )

            val result = assertIs<SearchRunEvent.Satisfied>(run.next())

            assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
            assertEquals(true, result.model.valueOf(SearchBoolValue(1)))
            assertEquals(1L, session.learnedClauseStats().restarts)
        }
    }
}
