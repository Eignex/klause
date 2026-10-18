package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.pipeline.bindOpenTheoryWork
import com.eignex.klause.solver.result.OpenTheoryWorkSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SearchSessionTest {

    @Test
    fun `shared learned conflicts should discard an earlier native resolver`() {
        val nativeClauses = ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3))))
        var resolutions = 0
        val native = object : SearchConflictResolver {
            override val retainsOwnExplanations: Boolean get() = true
            override fun initialize(context: SearchContext) = nativeClauses.initialize(context)
            override fun assert(decision: SearchDecision, context: SearchContext) =
                nativeClauses.assert(decision, context)
            override fun propagate(context: SearchContext) = nativeClauses.propagate(context)
            override fun retract(decisionLevel: Int) = nativeClauses.retract(decisionLevel)
            override fun resolveConflict(context: SearchContext): SearchConflictResolution {
                resolutions++
                return SearchConflictResolution.Chronological
            }
        }
        val session = SearchSession(listOf(native,
            ClauseSearchComponent(listOf(Clause(intArrayOf(1, 4)), Clause(intArrayOf(1, 5))))))
        assertIs<ComponentResult.Consistent>(session.initialize())
        val first = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(0)))
        val firstLearned = assertIs<SearchConflictResolution.Backjump>(
            session.explainedConflict(first.explanation),
        ).conflict
        assertContentEquals(intArrayOf(1), firstLearned.guardLiterals)
        session.learn(SearchExplanation(firstLearned.guardLiterals))
        session.popTo(0)
        val second = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(1)))
        val secondLearned = assertIs<SearchConflictResolution.Backjump>(
            session.explainedConflict(second.explanation),
        ).conflict
        assertContentEquals(intArrayOf(0), secondLearned.guardLiterals)
        session.popTo(0)
        assertTrue(session.hasNativeConflictResolver)

        val result = secondLearned.apply(session)

        assertEquals(SearchLearnedConflictResult.Chronological, result)
        assertEquals(2, session.learnedClauseCount)
        assertFalse(session.hasNativeConflictResolver)
        assertFalse(session.explanationIsOwnedElsewhere)
        assertNull(session.conflictResolution())
        assertEquals(0, resolutions)
    }

    @Test
    fun `component conflicts should retain their current native resolver after learned closure`() {
        val clauses = ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3))))
        var resolutions = 0
        val native = object : SearchConflictResolver {
            override val retainsOwnExplanations: Boolean get() = true
            override fun initialize(context: SearchContext) = clauses.initialize(context)
            override fun assert(decision: SearchDecision, context: SearchContext) = clauses.assert(decision, context)
            override fun propagate(context: SearchContext) = clauses.propagate(context)
            override fun retract(decisionLevel: Int) = clauses.retract(decisionLevel)
            override fun resolveConflict(context: SearchContext): SearchConflictResolution {
                resolutions++
                return SearchConflictResolution.Chronological
            }
        }
        val session = SearchSession(listOf(native))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.propagate())

        val result = session.push(SearchDecision.Bool(1))

        assertIs<ComponentResult.Conflict>(result)
        assertTrue(session.hasNativeConflictResolver)
        assertTrue(session.explanationIsOwnedElsewhere)
        val resolution = assertNotNull(session.conflictResolution())
        assertSame(native, resolution.first)
        assertEquals(SearchConflictResolution.Chronological, resolution.second)
        assertEquals(1, resolutions)
        assertFalse(session.hasNativeConflictResolver)
        assertFalse(session.explanationIsOwnedElsewhere)
    }

    @Test
    fun `learned contradictions should precede an indeterminate component while the containing scope is live`() {
        val clauses = listOf(
            Clause(intArrayOf(0, 2, 4)), Clause(intArrayOf(0, 2, 5)),
            Clause(intArrayOf(0, 3, 6)), Clause(intArrayOf(0, 3, 7)),
        )
        val derivation = SearchSession(listOf(ClauseSearchComponent(clauses)))
        assertIs<ComponentResult.Consistent>(derivation.initialize())
        assertIs<ComponentResult.Consistent>(derivation.push(SearchDecision.Bool(1)))
        val first = assertIs<ComponentResult.Conflict>(derivation.push(SearchDecision.Bool(3)))
        val firstLearned = assertIs<SearchConflictResolution.Backjump>(
            derivation.explainedConflict(first.explanation),
        ).conflict
        assertContentEquals(intArrayOf(0, 2), firstLearned.guardLiterals.sortedArray())
        derivation.popTo(firstLearned.decisionLevel)
        derivation.learn(SearchExplanation(firstLearned.guardLiterals))
        val second = assertIs<ComponentResult.Conflict>(derivation.propagate())
        val finalLearned = assertIs<SearchConflictResolution.Backjump>(
            derivation.explainedConflict(second.explanation),
        ).conflict
        assertContentEquals(intArrayOf(0), finalLearned.guardLiterals)
        val reason = SearchExplanation(finalLearned.guardLiterals)
        var undecided = false
        var propagations = 0
        val peer = object : SearchComponent {
            override fun propagate(context: SearchContext): ComponentResult {
                propagations++
                return if (undecided) ComponentResult.Indeterminate else ComponentResult.Consistent
            }
        }
        val session = SearchSession(listOf(ClauseSearchComponent(clauses), peer))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        session.learn(reason)
        undecided = true
        val before = propagations

        val result = session.propagate()

        val explanation = assertNotNull(assertIs<ComponentResult.Conflict>(result).explanation)
        assertContentEquals(intArrayOf(0), explanation.literals)
        assertEquals(before, propagations)
        assertEquals(1, session.decisionLevel)
        assertEquals(false, session.boolValue(0))
    }

    @Test
    fun `retained learned clauses should survive a root rebuild and repair reset`() {
        for (guarded in listOf(false, true)) {
            val prefix = if (guarded) intArrayOf(0, 2) else intArrayOf(0)
            val b = if (guarded) 2 else 1
            val clauses = listOf(
                Clause(prefix + intArrayOf(Lit.make(b, true), Lit.make(b + 1, true))),
                Clause(prefix + intArrayOf(Lit.make(b, true), Lit.make(b + 1, false))),
                Clause(prefix + intArrayOf(Lit.make(b, false), Lit.make(b + 2, true))),
                Clause(prefix + intArrayOf(Lit.make(b, false), Lit.make(b + 2, false))),
            )
            val derivation = SearchSession(listOf(ClauseSearchComponent(clauses)))
            assertIs<ComponentResult.Consistent>(derivation.initialize())
            assertIs<ComponentResult.Consistent>(derivation.push(SearchDecision.Bool(1)))
            if (guarded) assertIs<ComponentResult.Consistent>(derivation.push(SearchDecision.Bool(3)))
            val first = assertIs<ComponentResult.Conflict>(
                derivation.push(SearchDecision.Bool(Lit.make(b, false))),
            )
            val firstLearned = assertIs<SearchConflictResolution.Backjump>(
                derivation.explainedConflict(first.explanation),
            ).conflict
            assertContentEquals(prefix + Lit.make(b, true), firstLearned.guardLiterals.sortedArray())
            derivation.popTo(firstLearned.decisionLevel)
            derivation.learn(SearchExplanation(firstLearned.guardLiterals))
            val second = assertIs<ComponentResult.Conflict>(derivation.propagate())
            val learned = assertIs<SearchConflictResolution.Backjump>(
                derivation.explainedConflict(second.explanation),
            ).conflict
            assertContentEquals(prefix, learned.guardLiterals.sortedArray())
            for (bounded in listOf(false, true)) {
                val native = PropagationSession(Problem(b + 3, 0, emptyArray(), emptyArray()))
                val cp = CpSearchComponent(native)
                val session = SearchSession(listOf(cp, ClauseSearchComponent(clauses)))
                val work = OpenTheoryWorkSink(if (bounded) 1L else Long.MAX_VALUE)
                session.bindOpenTheoryWork(work)
                assertIs<ComponentResult.Consistent>(session.initialize())
                session.learn(SearchExplanation(learned.guardLiterals))
                assertIs<ComponentResult.Consistent>(session.propagate())
                var fragment = 0L
                val run = session.openRun(b + 3, decisionBudget = SearchDecisionBudget { --fragment >= 0L })
                assertEquals(SearchRunEvent.Indeterminate.Budget, run.next())
                assertTrue(session.decisionBudgetExhausted())
                assertEquals(SearchStopCause.DecisionAllowance, session.executionControls.stopCause)
                val spent = work.spent
                session.popTo(0)
                native.reseedFrom(Assumptions.None)
                cp.rebase()
                session.resetRootFacts()
                if (bounded) assertFalse(work.hasAllowance())

                val initialized = session.initialize()

                assertIs<ComponentResult.Consistent>(initialized)
                assertEquals(1, session.learnedClauseCount)
                if (guarded) {
                    assertNull(session.boolValue(0))
                    assertNull(session.boolValue(1))
                } else {
                    assertEquals(true, session.boolValue(0))
                    assertContentEquals(prefix, assertNotNull(session.reasonFor(0)).literals)
                }
                assertEquals(spent, work.spent)
                assertTrue(session.decisionBudgetExhausted())
                fragment = (b + 3).toLong()
                run.reset()
                assertTrue(session.decisionBudgetExhausted())
                if (bounded) {
                    assertEquals(SearchRunEvent.Indeterminate.Budget, run.next())
                    assertEquals(spent, work.spent)
                    assertEquals(SearchStopCause.WorkAllowance, session.executionControls.stopCause)
                } else {
                    assertIs<SearchRunEvent.Satisfied>(run.next())
                    assertTrue(work.spent > spent)
                    assertTrue(session.decisionBudgetExhausted())
                    if (guarded) {
                        assertEquals(false, session.boolValue(0))
                        assertEquals(true, session.boolValue(1))
                        assertContentEquals(prefix, assertNotNull(session.reasonFor(1)).literals.sortedArray())
                    }
                    for (clause in clauses) assertTrue(clause.literals.any {
                        session.boolValue(it ushr 1) == (it and 1 == 0)
                    })
                }
            }
        }
    }

    @Test
    fun `local operation declines should allow shared learned closure`() {
        val clauses = listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)))
        val derivation = SearchSession(listOf(ClauseSearchComponent(clauses)))
        assertIs<ComponentResult.Consistent>(derivation.initialize())
        val conflict = assertIs<ComponentResult.Conflict>(derivation.push(SearchDecision.Bool(1)))
        val reason = assertIs<SearchConflictResolution.Backjump>(
            derivation.explainedConflict(conflict.explanation),
        ).conflict
        assertContentEquals(intArrayOf(0), reason.guardLiterals)
        for (cause in listOf(SearchStopCause.OperationAllowance, SearchStopCause.RepresentationDecline)) {
            val controls = SearchExecutionControlAdapter()
            val session = SearchSession(listOf(ClauseSearchComponent(clauses)), executionControls = controls)
            assertIs<ComponentResult.Consistent>(session.initialize())
            session.learn(SearchExplanation(reason.guardLiterals))
            controls.reportStop(cause)

            val result = session.propagate()

            assertIs<ComponentResult.Consistent>(result)
            assertEquals(true, session.boolValue(0))
            assertContentEquals(intArrayOf(0), assertNotNull(session.reasonFor(0)).literals)
            assertEquals(cause, controls.stopCause)
        }
    }

    @Test
    fun `shared learned clauses should preserve completed fixpoints across slice pauses`() {
        for (shape in listOf("empty", "unit", "binary")) {
            val guarded = shape == "binary"
            val prefix = if (guarded) intArrayOf(0, 2) else intArrayOf(0)
            val b = if (guarded) 2 else 1
            val clauses = listOf(
                Clause(prefix + intArrayOf(Lit.make(b, true), Lit.make(b + 1, true))),
                Clause(prefix + intArrayOf(Lit.make(b, true), Lit.make(b + 1, false))),
                Clause(prefix + intArrayOf(Lit.make(b, false), Lit.make(b + 2, true))),
                Clause(prefix + intArrayOf(Lit.make(b, false), Lit.make(b + 2, false))),
            )
            val derivation = SearchSession(listOf(ClauseSearchComponent(clauses)))
            assertIs<ComponentResult.Consistent>(derivation.initialize())
            assertIs<ComponentResult.Consistent>(derivation.push(SearchDecision.Bool(1)))
            if (guarded) assertIs<ComponentResult.Consistent>(derivation.push(SearchDecision.Bool(3)))
            val first = assertIs<ComponentResult.Conflict>(
                derivation.push(SearchDecision.Bool(Lit.make(b, false))),
            )
            val firstLearned = assertIs<SearchConflictResolution.Backjump>(
                derivation.explainedConflict(first.explanation),
            ).conflict
            assertContentEquals(prefix + Lit.make(b, true), firstLearned.guardLiterals.sortedArray())
            derivation.popTo(firstLearned.decisionLevel)
            derivation.learn(SearchExplanation(firstLearned.guardLiterals))
            val second = assertIs<ComponentResult.Conflict>(derivation.propagate())
            val learned = assertIs<SearchConflictResolution.Backjump>(
                derivation.explainedConflict(second.explanation),
            ).conflict
            assertContentEquals(prefix, learned.guardLiterals.sortedArray())
            val native = PropagationSession(Problem(b + 3, 0, emptyArray(), emptyArray()), Cancellation.Never)
            val cp = CpSearchComponent(native)
            var sliceExpired = false
            var firstPause = true
            val signal = object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (firstPause && context.decisionLevel > 0 && decision is SearchDecision.Bool) {
                        firstPause = false
                        sliceExpired = true
                        context.requestCancellationPoll()
                    }
                    return ComponentResult.Consistent
                }
            }
            val session = SearchSession(listOf(cp, ClauseSearchComponent(clauses), signal),
                cancellation = Cancellation { sliceExpired })
            assertIs<ComponentResult.Consistent>(session.initialize())
            if (shape != "empty") {
                session.learn(SearchExplanation(learned.guardLiterals))
                assertIs<ComponentResult.Consistent>(session.propagate())
            }
            val firstVariable = (0 until b + 3).first { session.boolValue(it) == null }
            val run = session.openRun(b + 3, lifecycle = object : SearchRunLifecycle {
                override fun onCancellation(context: SearchContext) = SearchRunDisposition.Pause
            })

            val paused = run.next()

            assertEquals(SearchRunEvent.Paused, paused)
            assertEquals(false, session.boolValue(firstVariable))
            assertEquals(if (shape == "empty") 0 else 1, session.learnedClauseCount)
            if (guarded) {
                assertEquals(true, session.boolValue(1))
                assertContentEquals(prefix, assertNotNull(session.reasonFor(1)).literals.sortedArray())
            }
            val spent = native.work
            sliceExpired = false
            assertIs<SearchRunEvent.Satisfied>(run.next())
            assertTrue(native.work >= spent)
            assertNull(session.executionControls.stopCause)
            for (clause in clauses) assertTrue(clause.literals.any {
                session.boolValue(it ushr 1) == (it and 1 == 0)
            })
        }
    }

    @Test
    fun `component stops should preserve learned semantic facts for resumption`() {
        lateinit var session: SearchSession
        lateinit var clauses: ClauseSearchComponent
        var literal = -1
        var stopped = false
        val controls = SearchExecutionControlAdapter(Cancellation {
            stopped && literal >= 0 && session.boolValue(literal ushr 1) == true
        })
        val source = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult {
                val bound = assertNotNull(SearchIntegerBound.of(SearchDecision.IntAtMost(0, -1)))
                literal = assertNotNull(context.registerAtom(bound, bound.complement())).positive.literal
                clauses = ClauseSearchComponent(listOf(
                    Clause(intArrayOf(0, literal, 2)), Clause(intArrayOf(0, literal, 3)),
                ))
                return clauses.initialize(context)
            }
            override fun assert(decision: SearchDecision, context: SearchContext) = clauses.assert(decision, context)
            override fun propagate(context: SearchContext) = clauses.propagate(context)
            override fun retract(decisionLevel: Int) = clauses.retract(decisionLevel)
        }
        val delivered = mutableListOf<SearchDecision>()
        val peer = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                delivered += decision
                return ComponentResult.Consistent
            }
            override fun propagate(context: SearchContext): ComponentResult =
                if (context.cancelled()) ComponentResult.Indeterminate else ComponentResult.Consistent
        }
        session = SearchSession(listOf(source, peer), atoms = SearchAtomRegistry(2), executionControls = controls)
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(literal xor 1)))
        val learned = assertIs<SearchConflictResolution.Backjump>(
            session.explainedConflict(conflict.explanation),
        ).conflict
        assertContentEquals(intArrayOf(0, literal), learned.guardLiterals.sortedArray())
        session.popTo(learned.decisionLevel)
        val before = delivered.size
        stopped = true

        val interrupted = learned.apply(session)

        assertEquals(SearchLearnedConflictResult.Indeterminate, interrupted)
        assertEquals(true, session.boolValue(literal ushr 1))
        assertEquals(-1L, session.intUpperBound(0))
        assertEquals(1, delivered.count { it == SearchDecision.IntAtMost(0, -1) })
        assertTrue(delivered.size > before)
        val level = session.decisionLevel
        stopped = false
        session.popTo(level)
        assertIs<ComponentResult.Consistent>(session.propagate())
        assertEquals(-1L, session.intUpperBound(0))
        assertEquals(1, delivered.count { it == SearchDecision.IntAtMost(0, -1) })
        assertContentEquals(intArrayOf(literal xor 1), session.explainAtoms(session.intUpperBoundPremise(0))?.literals)
        assertNull(controls.stopCause)
        assertEquals(level, session.decisionLevel)
        session.popTo(0)
        assertNull(session.intUpperBound(0))
        assertNull(session.boolValue(literal ushr 1))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
    }

    @Test
    fun `initial learned clauses should report contradictions before component stop checks`() {
        for (guarded in listOf(false, true)) {
            val prefix = if (guarded) intArrayOf(0, 2) else intArrayOf(0)
            val b = if (guarded) 2 else 1
            val clauses = listOf(
                Clause(prefix + intArrayOf(Lit.make(b, true), Lit.make(b + 1, true))),
                Clause(prefix + intArrayOf(Lit.make(b, true), Lit.make(b + 1, false))),
                Clause(prefix + intArrayOf(Lit.make(b, false), Lit.make(b + 2, true))),
                Clause(prefix + intArrayOf(Lit.make(b, false), Lit.make(b + 2, false))),
            )
            val derivation = SearchSession(listOf(ClauseSearchComponent(clauses)))
            assertIs<ComponentResult.Consistent>(derivation.initialize())
            assertIs<ComponentResult.Consistent>(derivation.push(SearchDecision.Bool(1)))
            if (guarded) assertIs<ComponentResult.Consistent>(derivation.push(SearchDecision.Bool(3)))
            val first = assertIs<ComponentResult.Conflict>(
                derivation.push(SearchDecision.Bool(Lit.make(b, false))),
            )
            val firstLearned = assertIs<SearchConflictResolution.Backjump>(
                derivation.explainedConflict(first.explanation),
            ).conflict
            assertContentEquals(prefix + Lit.make(b, true), firstLearned.guardLiterals.sortedArray())
            derivation.popTo(firstLearned.decisionLevel)
            derivation.learn(SearchExplanation(firstLearned.guardLiterals))
            val second = assertIs<ComponentResult.Conflict>(derivation.propagate())
            val learned = assertIs<SearchConflictResolution.Backjump>(
                derivation.explainedConflict(second.explanation),
            ).conflict
            assertContentEquals(prefix, learned.guardLiterals.sortedArray())
            for (stopped in listOf(false, true)) {
                var cancelled = false
                var propagations = 0
                val owner = object : SearchComponent {
                    override fun propagate(context: SearchContext): ComponentResult {
                        propagations++
                        return if (context.cancelled()) ComponentResult.Indeterminate else ComponentResult.Consistent
                    }
                }
                val session = SearchSession(listOf(ClauseSearchComponent(clauses), owner),
                    cancellation = Cancellation { cancelled })
                assertIs<ComponentResult.Consistent>(session.initialize())
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
                if (guarded) assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))
                session.learn(SearchExplanation(learned.guardLiterals))
                val level = session.decisionLevel
                val before = propagations
                cancelled = stopped

                val result = session.propagate()

                val conflict = assertIs<ComponentResult.Conflict>(result)
                assertContentEquals(prefix, assertNotNull(conflict.explanation).literals.sortedArray())
                assertEquals(before, propagations)
                assertEquals(level, session.decisionLevel)
                assertEquals(false, session.boolValue(0))
                if (guarded) assertEquals(false, session.boolValue(1))
                for (variable in b..b + 2) assertNull(session.boolValue(variable))
                assertEquals(1, session.learnedClauseCount)
                assertFalse(session.hasNativeConflictResolver)
                assertFalse(session.explanationIsOwnedElsewhere)
            }
        }
    }

    @Test
    fun `open work should count each committed public decision kind`() {
        val decisions = listOf(
            SearchDecision.Bool(0) to Triple(1L, 0L, 0L),
            SearchDecision.IntAtMost(0, 1) to Triple(0L, 1L, 0L),
            SearchDecision.IntAtLeast(0, 0) to Triple(0L, 1L, 0L),
            SearchDecision.IntEqual(0, 0) to Triple(0L, 1L, 0L),
            SearchDecision.Theory(object : SearchTheoryDecision {}) to Triple(0L, 0L, 1L),
        )

        for ((decision, expected) in decisions) {
            val work = OpenTheoryWorkSink()
            val brancher = object : SearchBrancher {
                override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                    if (context.decisionLevel == 0) listOf(decision) else null
            }
            val component = object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
            }
            val session = SearchComponentSet(listOf(component), listOf(brancher)).session()
            session.bindOpenTheoryWork(work)

            assertIs<SearchResult.Satisfied>(session.solve(numBoolVars = 0))
            val stats = work.snapshot()
            assertEquals(expected, Triple(stats.openBoolDecisions, stats.openIntDecisions, stats.openTheoryDecisions))
            assertEquals(1L, stats.openWork)
        }
    }

    @Test
    fun `a refused sibling after fixed work exhaustion should be indeterminate`() {
        val work = OpenTheoryWorkSink(limit = 1)
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Infeasible()
            }),
        )
        session.bindOpenTheoryWork(work)

        val result = session.solve(numBoolVars = 1)

        assertIs<SearchResult.Indeterminate>(result)
        assertEquals(true, work.exhausted)
        assertEquals(1L, work.snapshot().openBoolDecisions)
        assertEquals(0L, work.snapshot().openTheoryChecks)
        assertEquals(1L, work.snapshot().openWork)
    }

    @Test
    fun `shared session should retract every component to the same decision level`() {
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
    fun `indeterminate component check should never become infeasible`() {
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Indeterminate
            }),
        )

        assertIs<ComponentCheck.Indeterminate>(session.check())
    }

    @Test
    fun `complete check should clear a resolver from an earlier conflict`() {
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
    fun `components should observe shared Boolean decisions without finite-domain state`() {
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
    fun `traversal-only brancher should supply alternatives beside one fixpoint component`() {
        val brancher = object : SearchBrancher {
            override fun nextBranch(context: SearchContext): List<SearchDecision> = listOf(SearchDecision.Bool(0))
        }
        val session = SearchComponentSet(
            components = listOf(object : SearchComponent {}),
            branchers = listOf(brancher),
        ).session()

        assertEquals(listOf(SearchDecision.Bool(0)), session.branchAlternatives())
    }

    @Test
    fun `component implication should reach peers and retract with its decision level`() {
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
    fun `component integer bounds should be shared semantically and retract with the trail`() {
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
    fun `native component import should reach peers without reasserting the source`() {
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
    fun `conflict retention should depend on whether the component keeps its own explanations`() {
        listOf(true to 0, false to 1).forEach { (retains, expectedCount) ->
            val session = SearchSession(listOf(ConflictingComponent(retains = retains)))

            drainToExhaustion(session.openRun(numBoolVars = 1))

            assertEquals(expectedCount, session.learnedClauseCount, "retains=$retains")
        }
    }

    private fun drainToExhaustion(run: SearchRun) {
        repeat(DRAIN_LIMIT) {
            if (run.next() is SearchRunEvent.Exhausted) return
        }
        error("traversal did not exhaust a single-variable space")
    }

    // The shared analyzer defers to a native resolver, so retention is the only way the conflict is kept.
    private class ConflictingComponent(private val retains: Boolean) : SearchConflictResolver {
        override val retainsOwnExplanations: Boolean get() = retains

        override fun propagate(context: SearchContext): ComponentResult = if (context.boolValue(0) == true) {
            ComponentResult.Conflict(SearchExplanation(intArrayOf(1)))
        } else {
            ComponentResult.Consistent
        }

        override fun resolveConflict(context: SearchContext): SearchConflictResolution =
            SearchConflictResolution.Chronological
    }

    @Test
    fun `session should assemble component-owned model values`() {
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
    fun `session should assemble assigned Boolean values under source keys`() {
        val session = SearchSession(emptyList())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

        assertEquals(true, session.model().valueOf<Boolean>(SearchBoolValue(0)))
    }

    @Test
    fun `Boolean driver should backtrack a component-refuted clause-consistent leaf`() {
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
    fun `Boolean driver should preserve an indeterminate leaf as unknown`() {
        val session = SearchSession(
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Indeterminate
            }),
        )

        assertIs<BooleanSearchResult.Indeterminate>(session.solveBoolean(1))
    }

    @Test
    fun `Boolean driver should retain a sound leaf explanation as a learned clause`() {
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
    fun `shared analysis should resolve a nonasserting theory conflict to first UIP`() {
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
    fun `a learned conflict whose propagation is indeterminate should leave the run unknown`() {
        // Not b0 and not b1 conflict, so the learned b0 or b1 backjumps into not b0, b1, which the theory cannot
        // decide; b0 is refuted without a learned clause. Nothing refutes that undecided region.
        val theory = object : SearchConflictResolver {
            override val prefersNativeConflictAnalysis: Boolean get() = false

            override fun propagate(context: SearchContext): ComponentResult = when {
                context.boolValue(0) == true -> ComponentResult.Conflict()

                context.boolValue(0) == false && context.boolValue(1) == false -> ComponentResult.Conflict(
                    SearchExplanation(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                )

                context.boolValue(0) == false && context.boolValue(1) == true -> ComponentResult.Indeterminate

                else -> ComponentResult.Consistent
            }

            override fun resolveConflict(context: SearchContext): SearchConflictResolution =
                SearchConflictResolution.Chronological
        }
        val session = SearchSession(listOf(theory))
        val run = session.openRun(numBoolVars = 2)

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<SearchRunEvent.Indeterminate>(run.next())
    }

    @Test
    fun `shared analysis should identify a root explanation as exhausted`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0)))

        assertIs<ComponentResult.Consistent>(session.propagate())

        assertEquals(
            SearchConflictResolution.Exhausted,
            session.explainedConflict(SearchExplanation(intArrayOf(1))),
        )
        assertEquals(0L, session.learnedClauseStats().assertingConflicts)
        assertEquals(0L, session.learnedClauseStats().nonAssertingConflicts)
    }

    @Test
    fun `generic driver should backtrack component branches through the shared trail`() {
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
    fun `generic driver should dispatch a typed theory branch through the shared trail`() {
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
    fun `node policy should learn at its asserting backjump level`() {
        var appliedAt: Int? = null
        var learnedBackjumps = 0
        val observer = object : SearchRunObserver {
            override fun onLearnedNodeBackjump() {
                learnedBackjumps++
            }
        }
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 2,
            nodePolicy = backjumpOnceAtLevelTwo(conflictLbd = 1, levels = intArrayOf(1)) {
                appliedAt = it.decisionLevel
            },
            observer = observer,
        )

        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(1, appliedAt)
        assertEquals(1, learnedBackjumps)
    }

    @Test
    fun `learned conflicts should feed restart quality and observer metadata before backjumping`() {
        var recorded: Pair<Int, Int>? = null
        var observedLevels: IntArray? = null
        val restart = object : SearchRestartPolicy {
            override fun recordConflict(lbd: Int, trailSize: Int) {
                recorded = lbd to trailSize
            }

            override fun shouldRestart(decisionsThisRun: Long): Boolean = false
        }
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 2,
            params = SearchSolveParams(restart = restart),
            nodePolicy = backjumpOnceAtLevelTwo(conflictLbd = 7, levels = intArrayOf(1, 4)),
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

    private fun backjumpOnceAtLevelTwo(
        conflictLbd: Int,
        levels: IntArray,
        onApply: (SearchSession) -> Unit = {},
    ): SearchNodePolicy {
        var pending = true
        return object : SearchNodePolicy {
            override fun beforeBranch(context: SearchContext): SearchNodeDisposition = if (
                pending && context.decisionLevel == 2
            ) {
                SearchNodeDisposition.Backjump(object : SearchLearnedConflict {
                    override val decisionLevel: Int = 1
                    override val lbd: Int = conflictLbd
                    override val guardLiterals: IntArray = intArrayOf(0)
                    override val decisionLevels: IntArray = levels

                    override fun apply(session: SearchSession): SearchLearnedConflictResult {
                        onApply(session)
                        pending = false
                        return SearchLearnedConflictResult.Resume
                    }
                })
            } else {
                SearchNodeDisposition.Expand
            }
        }
    }

    @Test
    fun `restart should retract shared state before notifying components`() {
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
    fun `generic driver should restart at a decision boundary and retain learned clauses`() {
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
        assertEquals(1L, session.learnedClauseStats().restarts)
        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `generic driver should return unknown when its decision allowance is spent`() {
        val session = SearchSession(emptyList())

        assertIs<SearchResult.Indeterminate>(session.solve(2, SearchSolveParams(maxDecisions = 1)))
    }

    @Test
    fun `a brancher that sees the deadline should stop the run before its branch is committed`() {
        var fired = false
        var asserted = 0
        val brancher = object : SearchBrancher {
            override fun nextBranch(context: SearchContext): List<SearchDecision>? {
                fired = true
                context.requestCancellationPoll()
                return listOf(SearchDecision.IntEqual(0, 0))
            }
        }
        val component = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                asserted++
                return ComponentResult.Consistent
            }
        }
        val session = SearchSession(listOf(brancher, component), cancellation = Cancellation { fired })

        val event = session.openRun(numBoolVars = 0).next()

        assertIs<SearchRunEvent.Indeterminate>(event)
        assertEquals(0, asserted)
    }

    @Test
    fun `rejected alternatives should not spend the committed decision allowance`() {
        val rejected = object : SearchTheoryDecision {}
        val accepted = object : SearchTheoryDecision {}
        val work = OpenTheoryWorkSink()
        val component = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                if ((decision as SearchDecision.Theory).decision === rejected) {
                    ComponentResult.Conflict()
                } else {
                    ComponentResult.Consistent
                }
        }
        val brancher = object : SearchBrancher {
            override fun nextBranch(context: SearchContext): List<SearchDecision>? = if (context.decisionLevel == 0) {
                listOf(SearchDecision.Theory(rejected), SearchDecision.Theory(accepted))
            } else {
                null
            }
        }
        val session = SearchComponentSet(listOf(component), listOf(brancher)).session()
        session.bindOpenTheoryWork(work)

        val result = session.solve(numBoolVars = 0, SearchSolveParams(maxDecisions = 1))

        assertIs<SearchResult.Satisfied>(result)
        assertEquals(1L, work.snapshot().openTheoryDecisions)
    }

    @Test
    fun `model policy should continue through a non-reportable model`() {
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
    fun `a skipped model should stop the run once the deadline has passed`() {
        var skipped = 0
        val session = SearchSession(emptyList(), cancellation = Cancellation { skipped > 0 })
        val run = session.openRun(
            numBoolVars = 10,
            modelPolicy = object : SearchModelPolicy {
                override fun onModel(model: AssembledSearchModel, context: SearchContext): SearchModelDisposition {
                    skipped++
                    return SearchModelDisposition.Skip
                }
            },
        )

        assertIs<SearchRunEvent.Indeterminate>(run.next())
        assertEquals(1, skipped)
    }

    @Test
    fun `a skipped root model should be exhausted after a cancellation pause`() {
        var cancelled = false
        var checked = 0
        val session = SearchSession(emptyList(), cancellation = Cancellation { cancelled })
        val run = session.openRun(
            numBoolVars = 0,
            modelPolicy = object : SearchModelPolicy {
                override fun onModel(model: AssembledSearchModel, context: SearchContext): SearchModelDisposition {
                    checked++
                    cancelled = true
                    return SearchModelDisposition.Skip
                }
            },
            lifecycle = object : SearchRunLifecycle {
                override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Pause
            },
        )
        assertIs<SearchRunEvent.Paused>(run.next())

        cancelled = false
        val result = run.next()

        assertIs<SearchRunEvent.Exhausted>(result)
        assertEquals(1, checked)
    }

    @Test
    fun `a skipped model should resume at its sibling after a cancellation pause`() {
        var cancelled = false
        var checked = 0
        val session = SearchSession(emptyList(), cancellation = Cancellation { cancelled })
        val run = session.openRun(
            numBoolVars = 1,
            modelPolicy = object : SearchModelPolicy {
                override fun onModel(model: AssembledSearchModel, context: SearchContext): SearchModelDisposition {
                    checked++
                    if (checked > 1) return SearchModelDisposition.Surface
                    cancelled = true
                    return SearchModelDisposition.Skip
                }
            },
            lifecycle = object : SearchRunLifecycle {
                override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Pause
            },
        )
        assertIs<SearchRunEvent.Paused>(run.next())

        cancelled = false
        val result = assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(true, result.model.valueOf<Boolean>(SearchBoolValue(0)))
        assertEquals(2, checked)
    }

    @Test
    fun `node policy should prune through the shared frame stack`() {
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
    fun `a sole component's publications should reach a fixpoint`() {
        val session = SearchSession(listOf(ExplainingPublisher(explains = false)))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(2)))

        assertEquals(true, session.boolValue(2))
        assertEquals(true, session.boolValue(3))
    }

    @Test
    fun `shared analysis should resolve through a literal its publisher explains`() {
        val session = SearchSession(listOf(ExplainingPublisher(explains = true)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(2)))

        val resolution = session.explainedConflict(SearchExplanation(intArrayOf(5, 7, 1)))

        val backjump = assertIs<SearchConflictResolution.Backjump>(resolution)
        assertEquals(1, backjump.conflict.decisionLevel)
        assertEquals(1L, session.learnedClauseStats().assertingConflicts)
        assertEquals(0L, session.learnedClauseStats().nonAssertingConflicts)
    }

    @Test
    fun `shared analysis should stay chronological when no publisher explains the literal`() {
        val session = SearchSession(listOf(ExplainingPublisher(explains = false)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(2)))

        val resolution = session.explainedConflict(SearchExplanation(intArrayOf(5, 7, 1)))

        assertEquals(SearchConflictResolution.Chronological, resolution)
        assertEquals(1, session.learnedClauseCount)
        assertEquals(0L, session.learnedClauseStats().assertingConflicts)
        assertEquals(1L, session.learnedClauseStats().nonAssertingConflicts)
    }

    private class ExplainingPublisher(private val explains: Boolean) : SearchComponent {
        override fun propagate(context: SearchContext): ComponentResult {
            if (context.boolValue(1) != true) return ComponentResult.Consistent
            if (context.boolValue(2) == null) return context.publish(4)
            if (context.boolValue(3) == null) return context.publish(6)
            return ComponentResult.Consistent
        }

        override fun reasonFor(literal: Int): SearchExplanation? =
            if (explains && literal == 6) SearchExplanation(intArrayOf(6, 5)) else null
    }

    private companion object {
        const val DRAIN_LIMIT = 8
    }

    private class RecordingComponent : SearchComponent {
        val retractions = ArrayList<Int>()

        override fun retract(decisionLevel: Int) {
            retractions.add(decisionLevel)
        }
    }

    @Test
    fun `pausing between failed siblings should retain the untried alternative`() {
        val attempted = ArrayList<Long>()
        val brancher = object : SearchBrancher {
            override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                if (context.intLowerBound(0) == null) {
                    listOf(SearchDecision.IntEqual(0, 0), SearchDecision.IntEqual(0, 1))
                } else {
                    null
                }
        }
        val refuter = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                val value = (decision as SearchDecision.IntEqual).value
                attempted += value
                return if (value == 0L) ComponentResult.Conflict() else ComponentResult.Consistent
            }
        }
        val session = SearchSession(listOf(brancher, refuter))
        val run = session.openRun(numBoolVars = 0)
        run.pauseBeforeDecision = { attempted.size == 1 }
        session.initialize()

        assertIs<SearchRunEvent.Paused>(run.next())
        assertEquals(listOf(0L), attempted)
        run.pauseBeforeDecision = { false }

        assertIs<SearchRunEvent.Satisfied>(run.next())
        assertEquals(listOf(0L, 1L), attempted)
    }

    @Test
    fun `a spent slice after the last rejected leaf should not replay the exhausted root`() {
        var checked = false
        val component = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck {
                checked = true
                return ComponentCheck.Infeasible()
            }
        }
        val session = SearchSession(listOf(component), cancellation = Cancellation { checked })
        val run = session.openRun(numBoolVars = 0)
        session.initialize()

        assertIs<SearchRunEvent.Exhausted>(run.next())
    }

    @Test
    fun `reset should discard a pending decision from a paused traversal`() {
        val session = SearchSession(emptyList())
        val run = session.openRun(numBoolVars = 1)
        run.pauseBeforeDecision = { true }
        session.initialize()
        assertIs<SearchRunEvent.Paused>(run.next())

        session.popTo(0)
        run.reset()
        run.pauseBeforeDecision = { false }

        assertIs<SearchRunEvent.Satisfied>(run.next())
    }
}
