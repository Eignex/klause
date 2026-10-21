package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.Impact
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.boundsMidpoint
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
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
    @Test
    fun `point variable proposals should preserve both branches for unobserved phases`() {
        for (enabled in listOf(false, true)) {
            val native = PropagationSession(Problem(2, 0, emptyArray(), emptyList()))
            val cp = CpSearchComponent(native, branching = CpBranching.None)
            val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(2))
            val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin,
                phaseSaving = enabled, randomSeed = 0L)
            val brancher = BacktrackBrancher(native, params, sink = null, restart = NoRestartSchedule)
            brancher.realPointPreference = { variable -> if (variable == VarRef.Bool(1)) 1L else null }
            brancher.realPointVariablePreference = { accept -> 1.takeIf(accept) }
            assertIs<ComponentResult.Consistent>(session.initialize())

            val alternatives = brancher.nextBranch(session)

            val variable = if (enabled) 1 else 0
            assertEquals(SearchDecision.Bool(Lit.make(variable, enabled)), alternatives?.first())
            assertEquals(setOf(SearchDecision.Bool(Lit.make(variable, false)),
                SearchDecision.Bool(Lit.make(variable, true))), alternatives?.toSet())
        }
    }

    @Test
    fun `point variable proposals should defer to retained phases after retraction`() {
        val native = PropagationSession(Problem(2, 0, emptyArray(), emptyList()))
        val cp = CpSearchComponent(native, branching = CpBranching.None)
        val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(2))
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin,
            phaseSaving = true, randomSeed = 0L)
        val brancher = BacktrackBrancher(native, params, sink = null, restart = NoRestartSchedule)
        brancher.realPointPreference = { variable -> if (variable == VarRef.Bool(1)) 1L else null }
        brancher.realPointVariablePreference = { accept -> 1.takeIf(accept) }
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
        session.popTo(0)

        val alternatives = brancher.nextBranch(session)

        assertEquals(SearchDecision.Bool(Lit.make(0, false)), alternatives?.first())
    }

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
