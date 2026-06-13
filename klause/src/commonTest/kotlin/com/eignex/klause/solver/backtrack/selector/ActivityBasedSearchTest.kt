package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActivityBasedSearchTest {

    @Test
    fun `ABS finds solutions in the engine`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3, 4), domainMin = 0, domainSize = 5)),
        )
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(
                variableSelector = ActivityBasedSearch(),
                valueSelector = IndomainMin,
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals((0..4).toSet(), sat.assignment.ints.toSet())
    }

    @Test
    fun `ABS scores vars by activity over domain size`() {
        // 3 unpinned vars; bump var 2's activity heavily; pick should return var 2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 9) },
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val abs = ActivityBasedSearch()
        // One pick to size the activity arrays before bumping.
        abs.pick(session, Random(0L))
        repeat(10) {
            abs.onPropagation(implied(intKeys = intArrayOf(2)))
            abs.onCommit(VarRef.IntVar(2))
        }
        val picked = abs.pick(session, Random(0L))
        assertEquals(
            VarRef.IntVar(2),
            picked,
            "ABS should prefer the var with bumped activity",
        )
    }

    @Test
    fun `ABS decay implicit via increment grows over commits`() {
        // Bump var 0 once at time t=0, then issue 100 onCommit calls (decaying),
        // then bump var 1 once at t=100. Activity of var 1 should exceed var 0 because
        // increment has grown.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 9) },
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val abs = ActivityBasedSearch(decay = 0.95)
        abs.pick(session, Random(0L))
        abs.onPropagation(implied(intKeys = intArrayOf(0)))
        // Pass time via commits so the increment grows.
        repeat(50) { abs.onCommit(VarRef.IntVar(0)) }
        // Bump var 1 with the now-larger increment.
        abs.onPropagation(implied(intKeys = intArrayOf(1)))
        val picked = abs.pick(session, Random(0L))
        assertEquals(
            VarRef.IntVar(1),
            picked,
            "recent bumps should outweigh older ones via implicit decay",
        )
    }

    @Test
    fun `ABS reset-on-restart clears state`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 9) },
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val abs = ActivityBasedSearch(resetOnRestart = true)
        abs.pick(session, Random(0L))
        abs.onPropagation(implied(intKeys = intArrayOf(1)))
        assertEquals(VarRef.IntVar(1), abs.pick(session, Random(0L)))
        abs.onRestart()
        // After restart, activity is reset → ties broken by id → var 0 wins.
        assertEquals(VarRef.IntVar(0), abs.pick(session, Random(0L)))
    }

    private fun implied(boolKeys: IntArray = IntArray(0), intKeys: IntArray = IntArray(0)): PropagationResult.Implied {
        // Internal ctor is module-scoped; commonTest sits in the same module so we can
        // call it directly. Aligned-length boolValues / intValues required.
        return PropagationResult.Implied(
            boolKeys,
            BooleanArray(boolKeys.size),
            intKeys,
            IntArray(intKeys.size),
        )
    }
}
