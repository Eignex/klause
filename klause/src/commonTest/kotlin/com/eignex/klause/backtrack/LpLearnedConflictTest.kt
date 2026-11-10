package com.eignex.klause.backtrack

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.bounding.LpSearchPolicy
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.solver.search.SearchModelContinuation
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRunDisposition
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchRunLifecycle
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpLearnedConflictTest {
    @Test
    fun `cancelled learned propagation should resume without redelivering accepted facts`() {
        for (nativeSat in listOf(false, true)) for (pauses in listOf(0, 1, 2)) {
            var cancelled = false
            var remainingPauses = pauses
            var issued = false
            var acceptedBefore = 0
            var acceptedLp = 0
            var acceptedAfter = 0
            var learnedCredits = 0
            var nodeCredits = 0
            val native = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()), nativeSat = nativeSat)
            val cp = CpSearchComponent(native, branching = CpBranching.None)
            val before = object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (decision == SearchDecision.Bool(Lit.make(1, true))) acceptedBefore++
                    return ComponentResult.Consistent
                }
            }
            val after = object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (decision == SearchDecision.Bool(Lit.make(1, true))) acceptedAfter++
                    return ComponentResult.Consistent
                }
            }
            LpPropagator(object : LpSearchPolicy {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (decision == SearchDecision.Bool(Lit.make(1, true))) {
                        if (remainingPauses > 0) {
                            remainingPauses--
                            cancelled = true
                            return ComponentResult.Indeterminate
                        }
                        acceptedLp++
                    }
                    return ComponentResult.Consistent
                }

                override fun check(context: SearchContext): ComponentCheck =
                    if (context.boolValue(0) == true || context.boolValue(1) == true) ComponentCheck.Feasible
                    else ComponentCheck.Infeasible()
            }).use { lp ->
                val session = SearchSession(listOf(cp, before, lp, after), cancellation = Cancellation { cancelled })
                assertIs<ComponentResult.Consistent>(session.initialize())
                val run = session.openRun(
                    3,
                    modelContinuation = SearchModelContinuation.Chronological,
                    nodePolicy = object : SearchNodePolicy {
                        override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                            if (issued || context.boolValue(0) != false) return SearchNodeDisposition.Expand
                            issued = true
                            return SearchNodeDisposition.Backjump(LpLearnedConflict(
                                Learned(intArrayOf(0, 2), native.decisionLevel, 2, intArrayOf(1, 2)), cp,
                            ))
                        }
                    },
                    observer = object : SearchRunObserver {
                        override fun onLearnedConflict(conflict: SearchLearnedConflict) { learnedCredits++ }
                        override fun onLearnedNodeBackjump() { nodeCredits++ }
                    },
                    lifecycle = object : SearchRunLifecycle {
                        override fun onResume(context: SearchContext): SearchRunDisposition {
                            if (issued) assertTrue(acceptedLp > 0)
                            return SearchRunDisposition.Continue
                        }

                        override fun onCancellation(context: SearchContext): SearchRunDisposition =
                            SearchRunDisposition.Pause
                    },
                )

                repeat(pauses) {
                    assertIs<SearchRunEvent.Paused>(run.next())
                    assertTrue(run.learnedPropagationPending)
                    assertFalse(run.retainBooleanRoot(Lit.make(0, false)))
                    assertEquals(1, acceptedBefore)
                    assertEquals(0, acceptedAfter)
                    assertEquals(1, native.learnedClauseCount)
                    assertEquals(1, learnedCredits)
                    assertEquals(1, nodeCredits)
                    assertFalse(native.fixpointCancelled)
                    assertIs<SearchRunEvent.Paused>(run.next())
                    cancelled = false
                }

                val models = ArrayList<List<Boolean>>()
                repeat(6) { index ->
                    val model = assertIs<SearchRunEvent.Satisfied>(run.next()).model
                    models += List(3) { checkNotNull(model.valueOf(SearchBoolValue(it))) }
                    if (index == 0) {
                        assertEquals(1, acceptedBefore)
                        assertEquals(1, acceptedLp)
                        assertEquals(1, acceptedAfter)
                    }
                }
                assertEquals(6, models.toSet().size)
                assertTrue(models.all { it[0] || it[1] })
                assertEquals(SearchRunEvent.Exhausted, run.next())
                assertFalse(run.learnedPropagationPending)
                assertEquals(1, native.learnedClauseCount)
                assertEquals(1, learnedCredits)
                assertEquals(1, nodeCredits)
                assertNull(session.executionControls.stopCause)
            }
        }
    }

    @Test
    fun `undecidable learned propagation should remain terminal without cancellation`() {
        for (pauseFirst in listOf(false, true)) {
            var cancelled = false
            var issued = false
            var assertions = 0
            val native = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()))
            val cp = CpSearchComponent(native, branching = CpBranching.None)
            LpPropagator(object : LpSearchPolicy {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    assertions++
                    if (pauseFirst && assertions == 1) cancelled = true
                    return ComponentResult.Indeterminate
                }
            }).use { lp ->
                val session = SearchSession(listOf(cp, lp), cancellation = Cancellation { cancelled })
                assertIs<ComponentResult.Consistent>(session.initialize())
                val run = session.openRun(2, nodePolicy = object : SearchNodePolicy {
                    override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                        if (issued) return SearchNodeDisposition.Expand
                        issued = true
                        return SearchNodeDisposition.Backjump(LpLearnedConflict(
                            Learned(intArrayOf(0), 0, 1, intArrayOf(0)), cp,
                        ))
                    }
                }, lifecycle = object : SearchRunLifecycle {
                    override fun onCancellation(context: SearchContext): SearchRunDisposition =
                        SearchRunDisposition.Pause
                })

                if (pauseFirst) {
                    assertIs<SearchRunEvent.Paused>(run.next())
                    cancelled = false
                }
                assertEquals(SearchRunEvent.Indeterminate.Component, run.next())
                assertFalse(run.learnedPropagationPending)
                assertEquals(SearchRunEvent.Indeterminate.Component, run.next())
                assertEquals(if (pauseFirst) 2 else 1, assertions)
            }
        }
    }

    @Test
    fun `hard cancelled learned propagation should remain terminal after rearming`() {
        var cancelled = false
        var issued = false
        var assertions = 0
        val native = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()))
        val cp = CpSearchComponent(native, branching = CpBranching.None)
        LpPropagator(object : LpSearchPolicy {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                assertions++
                cancelled = true
                return ComponentResult.Indeterminate
            }
        }).use { lp ->
            val session = SearchSession(listOf(cp, lp), cancellation = Cancellation { cancelled })
            assertIs<ComponentResult.Consistent>(session.initialize())
            val run = session.openRun(2, nodePolicy = object : SearchNodePolicy {
                override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                    if (issued) return SearchNodeDisposition.Expand
                    issued = true
                    return SearchNodeDisposition.Backjump(LpLearnedConflict(
                        Learned(intArrayOf(0), 0, 1, intArrayOf(0)), cp,
                    ))
                }
            })

            assertEquals(SearchRunEvent.Indeterminate.Component, run.next())
            cancelled = false
            assertEquals(SearchRunEvent.Indeterminate.Component, run.next())
            assertFalse(run.learnedPropagationPending)
            assertEquals(1, assertions)
        }
    }

    @Test
    fun `an interrupted native learned fixpoint should not acquire a shared continuation`() {
        var cancelled = false
        val native = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()),
            Cancellation { cancelled }, propagationCancelFloor = 0)
        val cp = CpSearchComponent(native, branching = CpBranching.None)
        val session = SearchSession(listOf(cp), cancellation = Cancellation { cancelled })
        assertIs<ComponentResult.Consistent>(session.initialize())
        val run = session.openRun(2, nodePolicy = object : SearchNodePolicy {
            override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                cancelled = true
                return SearchNodeDisposition.Backjump(LpLearnedConflict(
                    Learned(intArrayOf(0), 0, 1, intArrayOf(0)), cp,
                ))
            }
        }, lifecycle = object : SearchRunLifecycle {
            override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Pause
        })

        assertEquals(SearchRunEvent.Indeterminate.Component, run.next())
        assertTrue(native.fixpointCancelled)
        assertFalse(run.learnedPropagationPending)
        cancelled = false
        assertEquals(SearchRunEvent.Indeterminate.Component, run.next())
    }

    @Test
    fun `root reset should discard pending learned propagation with its abandoned trail`() {
        var cancelled = false
        var issued = false
        var lpAssertions = 0
        val native = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()))
        val cp = CpSearchComponent(native, branching = CpBranching.None)
        LpPropagator(object : LpSearchPolicy {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                if (!issued || decision != SearchDecision.Bool(2)) return ComponentResult.Consistent
                lpAssertions++
                cancelled = true
                return ComponentResult.Indeterminate
            }
        }).use { lp ->
            val session = SearchSession(listOf(cp, lp), cancellation = Cancellation { cancelled })
            assertIs<ComponentResult.Consistent>(session.initialize())
            val run = session.openRun(2, nodePolicy = object : SearchNodePolicy {
                override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                    if (issued || context.boolValue(0) != false) return SearchNodeDisposition.Expand
                    issued = true
                    return SearchNodeDisposition.Backjump(LpLearnedConflict(
                        Learned(intArrayOf(0, 2), native.decisionLevel, 2, intArrayOf(1, 2)), cp,
                    ))
                }
            }, lifecycle = object : SearchRunLifecycle {
                override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Pause
            })

            assertIs<SearchRunEvent.Paused>(run.next())
            assertTrue(run.learnedPropagationPending)
            session.popTo(0)
            run.reset()

            assertFalse(run.learnedPropagationPending)
            assertEquals(1, lpAssertions)
        }
    }

    @Test
    fun `a conflict after resumed learned propagation should explore the remaining region`() {
        var cancelled = false
        var issued = false
        var interrupted = false
        val native = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()))
        val cp = CpSearchComponent(native, branching = CpBranching.None)
        LpPropagator(object : LpSearchPolicy {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                if (issued && context.boolValue(0) == false && decision == SearchDecision.Bool(2)) {
                    if (!interrupted) {
                        interrupted = true
                        cancelled = true
                        return ComponentResult.Indeterminate
                    }
                    return ComponentResult.Conflict()
                }
                return ComponentResult.Consistent
            }

            override fun check(context: SearchContext): ComponentCheck =
                if (context.boolValue(0) == true) ComponentCheck.Feasible else ComponentCheck.Infeasible()
        }).use { lp ->
            val session = SearchSession(listOf(cp, lp), cancellation = Cancellation { cancelled })
            assertIs<ComponentResult.Consistent>(session.initialize())
            val run = session.openRun(2, modelContinuation = SearchModelContinuation.Chronological,
                nodePolicy = object : SearchNodePolicy {
                    override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                        if (issued || context.boolValue(0) != false) return SearchNodeDisposition.Expand
                        issued = true
                        return SearchNodeDisposition.Backjump(LpLearnedConflict(
                            Learned(intArrayOf(0, 2), native.decisionLevel, 2, intArrayOf(1, 2)), cp,
                        ))
                    }
                }, lifecycle = object : SearchRunLifecycle {
                    override fun onCancellation(context: SearchContext): SearchRunDisposition =
                        SearchRunDisposition.Pause
                })

            assertIs<SearchRunEvent.Paused>(run.next())
            cancelled = false

            repeat(2) {
                val model = assertIs<SearchRunEvent.Satisfied>(run.next()).model
                assertEquals(true, model.valueOf(SearchBoolValue(0)))
                assertEquals(it == 1, model.valueOf(SearchBoolValue(1)))
            }
            assertEquals(SearchRunEvent.Exhausted, run.next())
            assertFalse(run.learnedPropagationPending)
        }
    }
}
