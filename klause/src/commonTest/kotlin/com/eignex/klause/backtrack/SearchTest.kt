package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.Impact
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.boundsMidpoint
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
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
