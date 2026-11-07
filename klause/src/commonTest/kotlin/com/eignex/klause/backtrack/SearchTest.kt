package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.Impact
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.boundsMidpoint
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTest {
    private fun problemOf(vararg domains: IntDomain): Problem = Problem(
        numBoolVars = 0,
        numIntVars = domains.size,
        intDomains = arrayOf(*domains),
        factors = arrayOf<Factor>(),
    )

    @Test
    fun `branch observers receive accepted decisions at their committed depth`() {
        val attempts = ArrayList<SearchDecision>()
        val commits = ArrayList<Pair<SearchDecision, Int>>()
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin,
            componentFactory = { listOf(object : SearchComponent {}) },
            branchObserver = object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                    attempts.add(decision)
                }
                override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                    commits.add(decision to decisionLevel)
                }
            })

        val result = BacktrackSolver(problemOf(IntDomain(0, 1), IntDomain(0, 1)).bake()).solve(params)

        assertIs<SolveResult.Sat>(result)
        assertEquals(listOf(SearchDecision.IntAtMost(0, 0), SearchDecision.IntAtMost(1, 0)), attempts)
        assertEquals(attempts.mapIndexed { index, decision -> decision to index + 1 }, commits)
    }

    @Test
    fun `refuted branch attempts supply no commit callback`() {
        val attempts = ArrayList<SearchDecision>()
        val commits = ArrayList<SearchDecision>()
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin,
            componentFactory = { listOf(object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext) = ComponentResult.Conflict()
            }) },
            branchObserver = object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                    attempts.add(decision)
                }
                override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                    commits.add(decision)
                }
            })

        val result = BacktrackSolver(problemOf(IntDomain(0, 1)).bake()).solve(params)

        assertIs<SolveResult.Unsat>(result)
        assertEquals(2, attempts.size)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun `cancellation during an attempted branch supplies no commit callback`() {
        var cancelled = false
        val commits = ArrayList<SearchDecision>()
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin,
            componentFactory = { listOf(object : SearchComponent {}) },
            cancellation = Cancellation { cancelled }, branchObserver = object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                    cancelled = true
                }
                override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                    commits.add(decision)
                }
            })

        val result = BacktrackSolver(problemOf(IntDomain(0, 1)).bake()).solve(params)

        assertIs<SolveResult.Unknown>(result)
        assertTrue(cancelled)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun `a cooperative pause should observe sharing without ending the search`() {
        val events = mutableListOf<String>()
        val exchange = object : ClauseExchange {
            override fun onRestart(session: PropagationSession) = Unit
            override fun onSearchEnd(session: PropagationSession) { events += "end" }
            override fun onSearchPause(session: PropagationSession) { events += "pause" }
        }

        CpSatisfactionTraversal(
            problemOf(IntDomain(0, 1)).bake(),
            BacktrackParams(clauseExchange = exchange),
            sink = null,
            solveContext = LpSolveContext.Production,
            slice = TraversalSlice(pauses = { true }, beforeBranch = { true }),
        ).use { it.next() }

        assertEquals(listOf("pause"), events)
    }

    @Test
    fun `a non-enumerable domain decision should split at the bounds midpoint`() {
        val wideHi = 3_000_000_000L
        val session = PropagationSession(problemOf(IntDomain(0, wideHi)))
        assertNull(session.intDomain(0).spanOrNull())
        val mid = boundsMidpoint(session.intDomain(0))
        // A wide domain must bisect, or it peels one value per level (O(span) depth).
        val out = splitIntAlternatives(session, VarRef.IntVar(0), preferred = 0L).first()
        assertEquals(SearchDecision.IntAtMost(0, mid), out)
        assertTrue(mid in 1L until wideHi)
    }

    @Test
    fun `a node whose every value is refuted should backtrack instead of surfacing as a model`() {
        // Each probe of v0 forces v1 = v2 and fails, so Impact offers no value.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2),
                AllDifferent(intArrayOf(1, 2), domainMin = 0, domainSize = 2),
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 2),
            ),
        )
        val result = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(variableSelector = SmallestDomain, valueSelector = Impact(), randomSeed = 0L),
        )
        assertIs<SolveResult.Unsat>(result, "an odd cycle of disequalities over two values is infeasible")
    }

    @Test
    fun `a value probe that sees the deadline should ask the run to poll before it commits`() {
        val session = PropagationSession(problemOf(IntDomain(0, 3)), Cancellation { true })
        val params = BacktrackParams(variableSelector = SmallestDomain, valueSelector = Impact(), randomSeed = 0L)
        val brancher = BacktrackBrancher(session, params, sink = null, restart = RestartSchedule.from(params))
        val context = SearchSession(emptyList())

        val alternatives = brancher.nextBranch(context)

        assertTrue(!alternatives.isNullOrEmpty())
        assertTrue(context.consumeCancellationPollRequest())
        assertTrue(!session.probeCancelled)
    }

    @Test
    fun `an enumerable domain should split at the preferred value`() {
        val session = PropagationSession(problemOf(IntDomain(0, 10)))
        assertNotNull(session.intDomain(0).spanOrNull())
        val out = splitIntAlternatives(session, VarRef.IntVar(0), preferred = 0L).first()
        assertEquals(SearchDecision.IntAtMost(0, 0L), out)
    }
}
