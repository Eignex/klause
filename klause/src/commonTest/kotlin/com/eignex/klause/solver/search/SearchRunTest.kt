package com.eignex.klause.solver.search

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.bounding.LpSearchPolicy
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SearchRunTest {
    @Test
    fun `cancellation before blocking a retry model should preserve pending coverage`() {
        var checks = 0
        var cancelled = false
        val cp = CpSearchComponent(PropagationSession(Problem(1, 0, emptyArray(), emptyArray()).bake()),
            branching = CpBranching.None)
        val session = SearchSession(listOf(cp, object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck =
                if (++checks <= 2) ComponentCheck.Indeterminate else ComponentCheck.Feasible
        }), cancellation = ExecutionControl { cancelled })
        session.initialize()
        val run = session.openRun(1, modelContinuation = SearchModelContinuation.BlockAtRoot,
            lifecycle = object : SearchRunLifecycle {
                override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Exhausted
            })
        assertIs<SearchRunEvent.Satisfied>(run.next())
        cancelled = true

        val result = run.next()

        assertIs<SearchRunEvent.Indeterminate.Component>(result)
        assertEquals(3, checks)
    }

    @Test
    fun `resolved retries should block models shared by overlapping retained prefixes`() {
        var declined = 0
        var requestRestart = false
        var restarted = false
        var blocked = 0
        val cp = CpSearchComponent(PropagationSession(Problem(2, 0, emptyArray(), emptyArray()).bake()),
            branching = CpBranching.None)
        val checker = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                if (context.boolValue(0) == false && context.boolValue(1) == false) {
                    return if (++declined <= 2) ComponentCheck.Indeterminate else ComponentCheck.Feasible
                }
                requestRestart = true
                return ComponentCheck.Infeasible()
            }
        }
        val restart = object : SearchRestartPolicy {
            override fun shouldRestart(decisionsThisRun: Long): Boolean = requestRestart && !restarted
            override fun onRestart() {
                restarted = true
            }
        }
        val branching = object : BooleanBranching {
            override fun alternatives(context: SearchContext): List<SearchDecision>? {
                val order = if (restarted) listOf(1, 0) else listOf(0, 1)
                val variable = order.firstOrNull { context.boolValue(it) == null } ?: return null
                return listOf(SearchDecision.Bool(Lit.make(variable, false)),
                    SearchDecision.Bool(Lit.make(variable, true)))
            }
        }
        val session = SearchSession(listOf(cp, checker))
        session.initialize()
        val run = session.openRun(2, SearchSolveParams(maxDecisions = 32L, restart = restart),
            booleanBranching = branching, modelContinuation = SearchModelContinuation.BlockAtRoot,
            lifecycle = object : SearchRunLifecycle {
                override fun onModelBlocked(context: SearchContext): SearchRunDisposition {
                    blocked++
                    return SearchRunDisposition.Continue
                }
            })

        val model = assertIs<SearchRunEvent.Satisfied>(run.next()).model
        val terminal = run.next()

        assertEquals(false, model.valueOf(SearchBoolValue(0)))
        assertEquals(false, model.valueOf(SearchBoolValue(1)))
        assertIs<SearchRunEvent.Exhausted>(terminal)
        assertEquals(3, declined)
        assertEquals(1, blocked)
    }

    @Test
    fun `rejected replay accounting should not publish a decision commit`() {
        var recorded = 0
        var commits = 0
        val controls = SearchExecutionControlAdapter(accounting = object : SearchExecutionAccounting {
            override fun recordDecision(decision: SearchDecision): Boolean = ++recorded <= 2
        })
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Indeterminate
        }), executionControls = controls)
        session.initialize()
        val run = session.openRun(1, observer = object : SearchRunObserver {
            override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                commits++
            }
        })

        val result = run.next()

        assertIs<SearchRunEvent.Indeterminate.Budget>(result)
        assertEquals(2, commits)
        assertEquals(3, recorded)
    }

    @Test
    fun `a resolved retry should preserve the stop cause of an unresolved sibling`() {
        var checks = 0
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                if (++checks <= 2 || context.boolValue(0) == false) {
                    context.executionControls.reportStop(SearchStopCause.OperationAllowance)
                    return ComponentCheck.Indeterminate
                }
                return ComponentCheck.Feasible
            }
        }))
        session.initialize()
        val run = session.openRun(1)

        assertIs<SearchRunEvent.Satisfied>(run.next())
        val result = run.next()

        assertIs<SearchRunEvent.Indeterminate.Component>(result)
        assertEquals(SearchStopCause.OperationAllowance, session.executionControls.stopCause)
    }

    @Test
    fun `leaf retries should preserve the conditioned root`() {
        var checks = 0
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                assertEquals(true, context.boolValue(0))
                return if (++checks <= 2) ComponentCheck.Indeterminate else ComponentCheck.Infeasible()
            }
        }))
        session.initialize()
        session.push(SearchDecision.Bool(0))

        val result = session.openConditionedRun(2).next()

        assertIs<SearchRunEvent.Exhausted>(result)
        assertEquals(4, checks)
        assertEquals(true, session.boolValue(0))
        assertEquals(1, session.decisionLevel)
    }

    @Test
    fun `retry checks should spend the containing check allowance`() {
        var checks = 0
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                if (!context.consumeCheck()) return ComponentCheck.Indeterminate
                checks++
                return ComponentCheck.Indeterminate
            }
        }), maxChecks = 2L)
        session.initialize()

        val result = session.openRun(1).next()

        assertIs<SearchRunEvent.Indeterminate>(result)
        assertEquals(2, checks)
        assertEquals(SearchStopCause.CheckAllowance, session.executionControls.stopCause)
    }

    @Test
    fun `cancellation during replay should preserve unresolved coverage despite lifecycle exhaustion`() {
        var checks = 0
        var cancelled = false
        val session = SearchSession(listOf(object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                if (checks == 2) {
                    cancelled = true
                    return ComponentResult.Indeterminate
                }
                return ComponentResult.Consistent
            }

            override fun check(context: SearchContext): ComponentCheck {
                checks++
                return ComponentCheck.Indeterminate
            }
        }), cancellation = ExecutionControl { cancelled })
        session.initialize()
        val run = session.openRun(1, lifecycle = object : SearchRunLifecycle {
            override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Exhausted
        })

        val result = run.next()

        assertIs<SearchRunEvent.Indeterminate.Component>(result)
        assertEquals(2, checks)
    }

    @Test
    fun `undecided leaves should be retried after their siblings`() {
        val visited = ArrayList<Boolean>()
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                val value = checkNotNull(context.boolValue(0))
                visited += value
                return if (visited.size <= 2) {
                    context.executionControls.reportStop(SearchStopCause.OperationAllowance)
                    ComponentCheck.Indeterminate
                } else ComponentCheck.Feasible
            }
        }))
        session.initialize()
        val run = session.openRun(1)

        val first = assertIs<SearchRunEvent.Satisfied>(run.next()).model
        val second = assertIs<SearchRunEvent.Satisfied>(run.next()).model
        val terminal = run.next()

        assertEquals(listOf(false, true, false, true), visited)
        assertEquals(false, first.valueOf(SearchBoolValue(0)))
        assertEquals(true, second.valueOf(SearchBoolValue(0)))
        assertIs<SearchRunEvent.Exhausted>(terminal)
        assertNull(session.executionControls.stopCause)
    }

    @Test
    fun `refuted retries should establish exhaustion`() {
        var checks = 0
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck =
                if (++checks <= 2) ComponentCheck.Indeterminate else ComponentCheck.Infeasible()
        }))
        session.initialize()

        val result = session.openRun(1).next()

        assertIs<SearchRunEvent.Exhausted>(result)
        assertEquals(4, checks)
    }

    @Test
    fun `permanent declines should end after one retry pass`() {
        var checks = 0
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                checks++
                return ComponentCheck.Indeterminate
            }
        }))
        session.initialize()
        val run = session.openRun(1)

        val result = run.next()

        assertIs<SearchRunEvent.Indeterminate.Component>(result)
        assertSame(result, run.next())
        assertEquals(4, checks)
    }

    @Test
    fun `retry replay should spend the remaining decision allowance`() {
        var checks = 0
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                checks++
                return ComponentCheck.Indeterminate
            }
        }))
        session.initialize()

        val result = session.openRun(1, SearchSolveParams(maxDecisions = 2L)).next()

        assertIs<SearchRunEvent.Indeterminate.Budget>(result)
        assertEquals(2, checks)
        assertEquals(SearchStopCause.DecisionAllowance, session.executionControls.stopCause)
    }

    @Test
    fun `retry replay should resume an interrupted assertion without repeating completed leaves`() {
        var checks = 0
        var cancelled = false
        var interrupted = false
        var commits = 0
        val session = SearchSession(listOf(object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                if (checks == 2 && !interrupted) {
                    interrupted = true
                    cancelled = true
                    return ComponentResult.Indeterminate
                }
                return ComponentResult.Consistent
            }

            override fun check(context: SearchContext): ComponentCheck =
                if (++checks <= 2) ComponentCheck.Indeterminate else ComponentCheck.Infeasible()
        }), cancellation = ExecutionControl { cancelled })
        session.initialize()
        val run = session.openRun(1,
            observer = object : SearchRunObserver {
                override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                    commits++
                }
            },
            lifecycle = object : SearchRunLifecycle {
                override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Pause
            })

        assertIs<SearchRunEvent.Paused>(run.next())
        cancelled = false
        val result = run.next()

        assertIs<SearchRunEvent.Exhausted>(result)
        assertEquals(4, checks)
        assertEquals(4, commits)
    }

    @Test
    fun `reset should discard retained leaf retries`() {
        var decline = true
        var checks = 0
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                checks++
                return if (decline) ComponentCheck.Indeterminate else ComponentCheck.Infeasible()
            }
        }))
        session.initialize()
        val run = session.openRun(1)
        run.pauseBeforeDecision = { decline && checks == 2 }
        assertIs<SearchRunEvent.Paused>(run.next())
        session.popTo(0)
        run.reset()
        decline = false

        val result = run.next()

        assertIs<SearchRunEvent.Exhausted>(result)
        assertEquals(4, checks)
    }

    @Test
    fun `a deferred incumbent should be surfaced without blocking its unresolved assignment`() {
        var checks = 0
        var models = 0
        val cp = CpSearchComponent(PropagationSession(Problem(0, 0, emptyArray(), emptyArray()).bake()),
            branching = CpBranching.None)
        val session = SearchSession(listOf(cp, object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                checks++
                return ComponentCheck.Feasible
            }
        }))
        session.initialize()
        val run = session.openRun(0, modelContinuation = SearchModelContinuation.BlockAtRoot,
            modelPolicy = object : SearchModelPolicy {
                override fun onModel(model: AssembledSearchModel, context: SearchContext): SearchModelDisposition =
                    if (++models == 1) SearchModelDisposition.Retry(surface = true) else SearchModelDisposition.Continue
            })

        assertIs<SearchRunEvent.Satisfied>(run.next())
        val result = run.next()

        assertIs<SearchRunEvent.Exhausted>(result)
        assertEquals(2, checks)
        assertEquals(2, models)
    }

    @Test
    fun `a paused component check should resume before lifecycle or node work`() {
        var checks = 0
        var resumes = 0
        var nodes = 0
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck =
                if (++checks < 3) ComponentCheck.Paused else ComponentCheck.Feasible
        }))
        session.initialize()
        val run = session.openRun(0,
            lifecycle = object : SearchRunLifecycle {
                override fun onResume(context: SearchContext): SearchRunDisposition {
                    resumes++
                    return SearchRunDisposition.Continue
                }
            },
            nodePolicy = object : SearchNodePolicy {
                override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                    nodes++
                    return SearchNodeDisposition.Expand
                }
            })

        repeat(2) {
            assertIs<SearchRunEvent.Paused>(run.next())
            assertNull(session.executionControls.stopCause)
        }
        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(3, checks)
        assertEquals(1, resumes)
        assertEquals(1, nodes)
    }

    @Test
    fun `a resumed component decline should prevent exhaustion from proving infeasibility`() {
        var checks = 0
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck =
                if (++checks == 1) ComponentCheck.Paused else ComponentCheck.Indeterminate
        }))
        session.initialize()
        val run = session.openRun(0)

        assertIs<SearchRunEvent.Paused>(run.next())
        assertIs<SearchRunEvent.Indeterminate.Component>(run.next())

        assertEquals(SearchStopCause.RepresentationDecline, session.executionControls.stopCause)
    }

    @Test
    fun `a retained Boolean root should enumerate its region and reset to the whole source`() {
        val session = SearchSession(emptyList())
        session.initialize()
        val run = session.openRun(2, modelContinuation = SearchModelContinuation.Chronological)
        var pause = true
        run.pauseBeforeDecision = { pause && session.decisionLevel >= 1 }
        assertIs<SearchRunEvent.Paused>(run.next())

        assertTrue(run.retainBooleanRoot(Lit.make(0, false)))
        pause = false
        repeat(2) {
            val model = assertIs<SearchRunEvent.Satisfied>(run.next()).model
            assertEquals(false, model.valueOf(SearchBoolValue(0)))
            assertEquals(it == 1, model.valueOf(SearchBoolValue(1)))
        }
        assertIs<SearchRunEvent.Exhausted>(run.next())
        session.popTo(0)
        run.reset()

        repeat(4) {
            val model = assertIs<SearchRunEvent.Satisfied>(run.next()).model
            assertEquals(it >= 2, model.valueOf(SearchBoolValue(0)))
            assertEquals(it % 2 == 1, model.valueOf(SearchBoolValue(1)))
        }
        assertIs<SearchRunEvent.Exhausted>(run.next())
    }

    @Test
    fun `a mismatched retained Boolean root should leave the whole traversal intact`() {
        val session = SearchSession(emptyList())
        session.initialize()
        val run = session.openRun(2, modelContinuation = SearchModelContinuation.Chronological)
        var pause = true
        run.pauseBeforeDecision = { pause && session.decisionLevel >= 1 }
        assertIs<SearchRunEvent.Paused>(run.next())

        assertFalse(run.retainBooleanRoot(Lit.make(0, true)))
        pause = false

        repeat(4) {
            val model = assertIs<SearchRunEvent.Satisfied>(run.next()).model
            assertEquals(it >= 2, model.valueOf(SearchBoolValue(0)))
            assertEquals(it % 2 == 1, model.valueOf(SearchBoolValue(1)))
        }
        assertIs<SearchRunEvent.Exhausted>(run.next())
    }

    @Test
    fun `restarts and learned conflicts should preserve a retained Boolean root`() {
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = if (context.boolValue(1) == false) {
                ComponentCheck.Infeasible(SearchExplanation(intArrayOf(Lit.make(1, true))))
            } else ComponentCheck.Feasible
        }))
        session.initialize()
        var pause = true
        val run = session.openRun(2, SearchSolveParams(maxDecisions = 4, restart = SearchRestart.Every(1)),
            modelContinuation = SearchModelContinuation.Chronological,
            nodePolicy = object : SearchNodePolicy {
                override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                    if (pause && context.decisionLevel >= 1) return SearchNodeDisposition.Pause
                    return SearchNodeDisposition.Expand
                }
            })
        assertIs<SearchRunEvent.Paused>(run.next())

        assertTrue(run.retainBooleanRoot(Lit.make(0, false)))
        pause = false
        val model = assertIs<SearchRunEvent.Satisfied>(run.next()).model

        assertEquals(false, model.valueOf(SearchBoolValue(0)))
        assertEquals(true, model.valueOf(SearchBoolValue(1)))
        assertEquals(2L, session.learnedClauseStats().restarts)
        assertIs<SearchRunEvent.Exhausted>(run.next())
    }

    @Test
    fun `root reset should retire an exhausted fragment budget`() {
        var remaining = 1
        var prune = false
        val session = SearchSession(emptyList())
        session.initialize()
        val run = session.openRun(1, decisionBudget = SearchDecisionBudget { remaining-- > 0 },
            nodePolicy = object : SearchNodePolicy {
                override fun beforeBranch(context: SearchContext): SearchNodeDisposition =
                    if (prune) SearchNodeDisposition.Prune else SearchNodeDisposition.Expand
            })
        assertIs<SearchRunEvent.Satisfied>(run.next())
        assertEquals(SearchRunEvent.Indeterminate.Budget, run.next())
        session.popTo(0)
        prune = true

        run.reset()
        val completed = run.next()

        assertEquals(SearchRunEvent.Exhausted, completed)
        assertNull(session.executionControls.stopCause)
    }

    @Test
    fun `a cancelled assertion should retry its unrefuted alternative after a pause`() {
        for (cancelAfterConflict in listOf(false, true)) {
            var cancelled = false
            var interrupted = false
            val assertions = ArrayList<SearchDecision>()
            val component = object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    assertions += decision
                    if (cancelAfterConflict && assertions.size == 1) return ComponentResult.Conflict()
                    if (!interrupted) {
                        interrupted = true
                        cancelled = true
                        return ComponentResult.Indeterminate
                    }
                    return ComponentResult.Consistent
                }
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
            }
            val session = SearchSession(listOf(component), cancellation = ExecutionControl { cancelled })
            session.initialize()
            val run = session.openRun(1, lifecycle = object : SearchRunLifecycle {
                override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Pause
            })

            assertIs<SearchRunEvent.Paused>(run.next())
            assertEquals(0, session.decisionLevel)
            val pending = assertions.last()
            cancelled = false
            val result = run.next()

            assertIs<SearchRunEvent.Satisfied>(result)
            assertEquals(pending, assertions.last())
            assertEquals(if (cancelAfterConflict) 3 else 2, assertions.size)
        }
    }

    @Test
    fun `a cancelled leaf check should retry before exhausting completed coverage`() {
        for (continuation in listOf(SearchModelContinuation.Chronological, SearchModelContinuation.BlockAtRoot)) {
            var cancelled = false
            var interrupted = false
            val checks = ArrayList<Boolean>()
            val cp = CpSearchComponent(
                PropagationSession(Problem(1, 0, emptyArray(), emptyArray()).bake()),
                branching = CpBranching.None,
            )
            val component = object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck {
                    checks += checkNotNull(context.boolValue(0))
                    if (!interrupted) {
                        interrupted = true
                        cancelled = true
                        return ComponentCheck.Indeterminate
                    }
                    return ComponentCheck.Feasible
                }
            }
            val session = SearchSession(listOf(cp, component), cancellation = ExecutionControl { cancelled })
            session.initialize()
            val run = session.openRun(1, modelContinuation = continuation, lifecycle = object : SearchRunLifecycle {
                override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Pause
            })

            assertIs<SearchRunEvent.Paused>(run.next())
            assertEquals(listOf(false), checks)
            cancelled = false

            assertEquals(false, assertIs<SearchRunEvent.Satisfied>(run.next()).model.valueOf(SearchBoolValue(0)))
            assertEquals(true, assertIs<SearchRunEvent.Satisfied>(run.next()).model.valueOf(SearchBoolValue(0)))
            assertEquals(SearchRunEvent.Exhausted, run.next())
            assertEquals(listOf(false, false, true), checks)
        }
    }

    @Test
    fun `a cancellation pause should preserve genuinely unresolved sibling coverage`() {
        for (continuation in listOf(SearchModelContinuation.Chronological, SearchModelContinuation.BlockAtRoot)) {
            for (unresolvedBeforePause in listOf(false, true)) {
                var cancelled = false
                var interrupted = false
                val cp = CpSearchComponent(
                    PropagationSession(Problem(1, 0, emptyArray(), emptyArray()).bake()),
                    branching = CpBranching.None,
                )
                val component = object : SearchComponent {
                    override fun check(context: SearchContext): ComponentCheck {
                        val value = checkNotNull(context.boolValue(0))
                        if (!interrupted && value == unresolvedBeforePause) {
                            interrupted = true
                            cancelled = true
                            return ComponentCheck.Indeterminate
                        }
                        return if (value) ComponentCheck.Feasible else ComponentCheck.Indeterminate
                    }
                }
                val session = SearchSession(listOf(cp, component), cancellation = ExecutionControl { cancelled })
                session.initialize()
                val run = session.openRun(1, modelContinuation = continuation, lifecycle = object : SearchRunLifecycle {
                    override fun onCancellation(context: SearchContext): SearchRunDisposition =
                        SearchRunDisposition.Pause
                })

                assertIs<SearchRunEvent.Paused>(run.next())
                cancelled = false

                assertEquals(true, assertIs<SearchRunEvent.Satisfied>(run.next()).model.valueOf(SearchBoolValue(0)))
                assertEquals(SearchRunEvent.Indeterminate.Component, run.next())
            }
        }
    }

    @Test
    fun `cancellation should retain unresolved coverage when the lifecycle reports exhaustion`() {
        var cancelled = false
        val component = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                cancelled = true
                return ComponentCheck.Indeterminate
            }
        }
        val session = SearchSession(listOf(component), cancellation = ExecutionControl { cancelled })
        session.initialize()
        val run = session.openRun(0, lifecycle = object : SearchRunLifecycle {
            override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Exhausted
        })

        val result = run.next()

        assertEquals(SearchRunEvent.Indeterminate.Component, result)
    }

    @Test
    fun `cancelled model blocking should retain a completed model across slices`() {
        for (disposition in listOf(SearchModelDisposition.Surface, SearchModelDisposition.Continue)) {
            var cancelled = false
            val models = ArrayList<Boolean>()
            val cp = CpSearchComponent(
                PropagationSession(Problem(1, 0, emptyArray(), emptyArray()).bake()),
                branching = CpBranching.None,
            )
            LpPropagator(object : LpSearchPolicy {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
            }).use { lp ->
                val session = SearchSession(listOf(cp, lp), cancellation = ExecutionControl { cancelled })
                session.initialize()
                val run = session.openRun(
                    1,
                    modelContinuation = SearchModelContinuation.BlockAtRoot,
                    modelPolicy = object : SearchModelPolicy {
                        override fun onModel(
                            model: AssembledSearchModel,
                            context: SearchContext,
                        ): SearchModelDisposition {
                            models += checkNotNull(model.valueOf(SearchBoolValue(0)))
                            if (models.size == 1) cancelled = true
                            return disposition
                        }
                    },
                    lifecycle = object : SearchRunLifecycle {
                        override fun onCancellation(context: SearchContext): SearchRunDisposition =
                            SearchRunDisposition.Pause
                    },
                )

                val first = run.next()
                if (disposition == SearchModelDisposition.Surface) {
                    assertEquals(false, assertIs<SearchRunEvent.Satisfied>(first).model.valueOf(SearchBoolValue(0)))
                    assertIs<SearchRunEvent.Paused>(run.next())
                } else {
                    assertIs<SearchRunEvent.Paused>(first)
                }
                assertEquals(listOf(false), models)
                assertEquals(false, session.boolValue(0))
                assertEquals(1, session.decisionLevel)
                cancelled = false

                val resumed = run.next()
                if (disposition == SearchModelDisposition.Surface) {
                    assertEquals(true, assertIs<SearchRunEvent.Satisfied>(resumed).model.valueOf(SearchBoolValue(0)))
                    assertEquals(SearchRunEvent.Exhausted, run.next())
                } else {
                    assertEquals(SearchRunEvent.Exhausted, resumed)
                }
                assertEquals(listOf(false, true), models)
                assertNull(session.executionControls.stopCause)
            }
        }
    }

    @Test
    fun `a hard cancellation before model blocking should remain terminal after rearming`() {
        var cancelled = false
        val cp = CpSearchComponent(
            PropagationSession(Problem(1, 0, emptyArray(), emptyArray()).bake()),
            branching = CpBranching.None,
        )
        LpPropagator(object : LpSearchPolicy {
            override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
        }).use { lp ->
            val session = SearchSession(listOf(cp, lp), cancellation = ExecutionControl { cancelled })
            session.initialize()
            val run = session.openRun(1, modelContinuation = SearchModelContinuation.BlockAtRoot)
            assertIs<SearchRunEvent.Satisfied>(run.next())
            cancelled = true

            val result = run.next()

            assertEquals(SearchRunEvent.Indeterminate.Component, result)
            assertEquals(SearchStopCause.Cancelled, session.executionControls.stopCause)
            cancelled = false
            assertSame(result, run.next())
        }
    }

    @Test
    fun `a noncancelled model block decline should preserve incomplete coverage`() {
        val cp = CpSearchComponent(
            PropagationSession(Problem(1, 0, emptyArray(), emptyArray()).bake()),
            branching = CpBranching.None,
        )
        LpPropagator(object : LpSearchPolicy {
            override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
        }).use { lp ->
            val session = SearchSession(listOf(cp, lp))
            session.initialize()
            val run = session.openRun(1, modelContinuation = SearchModelContinuation.BlockAtRoot)
            assertIs<SearchRunEvent.Satisfied>(run.next())
            lp.close()

            val result = run.next()

            assertEquals(SearchRunEvent.Indeterminate.Component, result)
            assertSame(result, run.next())
        }
    }

    @Test
    fun `component stop snapshots should survive cached consumption after cancellation is rearmed`() {
        for (record in listOf(false, true)) {
            var interruptBlock = false
            var cancelled = false
            val cp = CpSearchComponent(
                PropagationSession(Problem(1, 0, emptyArray(), emptyArray()).bake()),
                branching = CpBranching.None,
            )
            LpPropagator(object : LpSearchPolicy {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
                override fun propagate(context: SearchContext): ComponentResult {
                    if (!interruptBlock) return ComponentResult.Consistent
                    cancelled = true
                    return ComponentResult.Indeterminate
                }
            }).use { lp ->
                val session = SearchSession(listOf(cp, lp), cancellation = ExecutionControl { cancelled })
                session.initialize()
                val run = session.openRun(1, modelContinuation = SearchModelContinuation.BlockAtRoot).also {
                    it.recordComponentStops = record
                }
                assertIs<SearchRunEvent.Satisfied>(run.next())
                interruptBlock = true

                val terminal = run.next()
                val stop = run.componentStop

                assertEquals(SearchRunEvent.Indeterminate.Component, terminal)
                if (record) {
                    assertEquals(
                        SearchComponentStop(SearchComponentStop.Site.ModelBlock, SearchStopCause.Cancelled,
                            cancelled = true, unresolvedCoverage = false, decisionLevel = 0),
                        stop,
                    )
                } else {
                    assertNull(stop)
                }
                assertFalse(run.componentStopReplayed)
                cancelled = false
                assertSame(terminal, run.next())
                assertEquals(record, run.componentStopReplayed)
                assertSame(stop, run.componentStop)
                assertNull(session.executionControls.stopCause)
            }
        }
    }

    @Test
    fun `component stop snapshots should reset with their traversal`() {
        var decline = true
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
        }))
        session.initialize()
        val run = session.openRun(0, nodePolicy = object : SearchNodePolicy {
            override fun beforeBranch(context: SearchContext): SearchNodeDisposition =
                if (decline) SearchNodeDisposition.Indeterminate else SearchNodeDisposition.Expand
        }).also { it.recordComponentStops = true }
        assertEquals(SearchRunEvent.Indeterminate.Component, run.next())
        assertEquals(SearchComponentStop.Site.NodePolicy, run.componentStop?.site)
        run.next()
        assertTrue(run.componentStopReplayed)

        run.reset()
        decline = false

        assertNull(run.componentStop)
        assertFalse(run.componentStopReplayed)
        assertNull(session.executionControls.stopCause)
        assertIs<SearchRunEvent.Satisfied>(run.next())
        assertEquals(SearchRunEvent.Exhausted, run.next())
        assertNull(run.componentStop)
    }

    @Test
    fun `component stop sites should distinguish model and lifecycle boundaries`() {
        val sites = listOf(SearchComponentStop.Site.Start, SearchComponentStop.Site.Resume,
            SearchComponentStop.Site.ModelBlocked,
            SearchComponentStop.Site.ModelPolicy, SearchComponentStop.Site.ExecutionControl)
        for (site in sites) {
            var cancelled = false
            val cp = CpSearchComponent(
                PropagationSession(Problem(1, 0, emptyArray(), emptyArray()).bake()),
                branching = CpBranching.None,
            )
            val session = SearchSession(listOf(cp), cancellation = ExecutionControl { cancelled })
            session.initialize()
            val run = session.openRun(
                1,
                modelContinuation = SearchModelContinuation.BlockAtRoot,
                modelPolicy = object : SearchModelPolicy {
                    override fun onModel(model: AssembledSearchModel, context: SearchContext): SearchModelDisposition =
                        if (site == SearchComponentStop.Site.ModelPolicy) SearchModelDisposition.Indeterminate
                        else SearchModelDisposition.Surface
                },
                lifecycle = object : SearchRunLifecycle {
                    override fun onStart(context: SearchContext): SearchRunDisposition =
                        if (site == SearchComponentStop.Site.Start) SearchRunDisposition.Indeterminate
                        else SearchRunDisposition.Continue
                    override fun onResume(context: SearchContext): SearchRunDisposition =
                        if (site == SearchComponentStop.Site.Resume) SearchRunDisposition.Indeterminate
                        else SearchRunDisposition.Continue
                    override fun onModelBlocked(context: SearchContext): SearchRunDisposition =
                        if (site == SearchComponentStop.Site.ModelBlocked) SearchRunDisposition.Indeterminate
                        else SearchRunDisposition.Continue
                },
            ).also { it.recordComponentStops = true }

            var result = run.next()
            if (result is SearchRunEvent.Satisfied) {
                cancelled = site == SearchComponentStop.Site.ExecutionControl
                result = run.next()
            }

            assertEquals(SearchRunEvent.Indeterminate.Component, result)
            val stop = assertIs<SearchComponentStop>(run.componentStop)
            assertEquals(site, stop.site)
            assertEquals(site == SearchComponentStop.Site.ExecutionControl, stop.cancelled)
            assertFalse(stop.unresolvedCoverage)
            val retainedModel = site == SearchComponentStop.Site.ModelPolicy ||
                site == SearchComponentStop.Site.ExecutionControl
            assertEquals(
                if (retainedModel) 1 else 0,
                stop.decisionLevel,
            )
        }
    }

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
        val session = SearchSession(emptyList(), cancellation = ExecutionControl { cancelled })
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
