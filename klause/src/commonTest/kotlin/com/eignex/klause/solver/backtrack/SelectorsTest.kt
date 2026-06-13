package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.selector.Chb
import com.eignex.klause.solver.backtrack.selector.DomainMaxRegret
import com.eignex.klause.solver.backtrack.selector.IndomainMedian
import com.eignex.klause.solver.backtrack.selector.IndomainMiddle
import com.eignex.klause.solver.backtrack.selector.IndomainSplit
import com.eignex.klause.solver.backtrack.selector.LargestUpperBound
import com.eignex.klause.solver.backtrack.selector.SmallestLowerBound
import com.eignex.klause.solver.backtrack.selector.VarRef
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectorsTest {

    private val rng = Random(1)

    @Test
    fun `smallest lower bound prefers the int with the lowest minimum`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(2, 9), IntDomain(-3, 9)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        // Free bool counts as minimum 0; int 1's minimum of -3 undercuts it.
        assertEquals(VarRef.IntVar(1), SmallestLowerBound.pick(session, rng))
    }

    @Test
    fun `max regret prefers the int with the largest gap between its two smallest values`() {
        // var 0: {0,1,2,3} regret 1; var 1: {0,2,3} (1 excluded) regret 2 — var 1 wins.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3).excludeValue(1)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(VarRef.IntVar(1), DomainMaxRegret.pick(session, rng))
    }

    @Test
    fun `indomain_median is the middle by position - distinct from indomain_middle mean of bounds`() {
        // Domain {0,1,2,3,10}: median by position is valueAt(2) = 2; the mean of bounds is 5,
        // whose nearest present value is 3. So the two heuristics start on different values.
        var d = IntDomain(0, 10)
        for (v in 4..9) d = d.excludeValue(v)
        val problem = Problem(0, 1, arrayOf(d), arrayOf<Factor>())
        val session = PropagationSession(problem)
        assertEquals(2, IndomainMedian.values(session, VarRef.IntVar(0), rng).first())
        assertEquals(3, IndomainMiddle.values(session, VarRef.IntVar(0), rng).first())
    }

    @Test
    fun `smallest lower bound counts free bools as zero`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(2, 9)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(VarRef.Bool(0), SmallestLowerBound.pick(session, rng))
    }

    @Test
    fun `largest upper bound prefers the int with the highest maximum`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 7)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(VarRef.IntVar(1), LargestUpperBound.pick(session, rng))
    }

    @Test
    fun `indomain split yields the interval midpoint first`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        val values = IndomainSplit.values(session, VarRef.IntVar(0), rng).toList()
        assertEquals(5, values.first())
        // The trailing walk completes the domain without repeating the midpoint.
        assertEquals((0..10).toList().sorted(), values.sorted())
    }

    @Test
    fun `indomain split midpoint respects a shifted interval`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(10, 13)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(11, IndomainSplit.values(session, VarRef.IntVar(0), rng).first())
    }

    @Test
    fun `chb branches first on the variable most recently in a conflict`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = arrayOf<IntDomain>(),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        val chb = Chb()
        // No conflicts yet: all scores are 0, so the tie breaks to the lowest var id.
        assertEquals(VarRef.Bool(0), chb.pick(session, rng))
        // A conflict implicating var 2 lifts its Q above every untouched variable.
        chb.onConflict(VarRef.Bool(2), PropagationResult.Unsat(conflictBools = intArrayOf(2)))
        assertEquals(VarRef.Bool(2), chb.pick(session, rng))
    }

    @Test
    fun `chb solves a satisfiable clause problem with a valid witness`() {
        // (x0 ∨ x1) ∧ (¬x0 ∨ x2) ∧ (¬x1 ∨ ¬x2): satisfiable.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = arrayOf<IntDomain>(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 1L, variableSelector = Chb())),
        )
        val b = sat.assignment.bools
        assertTrue(b[0] || b[1])
        assertTrue(!b[0] || b[2])
        assertTrue(!b[1] || !b[2])
    }

    @Test
    fun `chb reports unsat on a contradiction`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = arrayOf<IntDomain>(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        assertIs<SolveResult.Unsat>(
            BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 1L, variableSelector = Chb())),
        )
    }
}
