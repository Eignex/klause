package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SolutionGuidedTest {

    @Test
    fun `before any solution it delegates to base verbatim`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val guided = SolutionGuided(IndomainMin)
        val values = guided.values(session, VarRef.IntVar(0), Random(0L)).toList()
        // IndomainMin → 0, 1, 2, 3, 4
        assertEquals(listOf(0, 1, 2, 3, 4), values)
    }

    @Test
    fun `after a solution saved value is tried first`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val guided = SolutionGuided(IndomainMin)
        // Synthetic incumbent: v0 = 3.
        guided.onSolution(Sample(BooleanArray(0), intArrayOf(3)))
        val values = guided.values(session, VarRef.IntVar(0), Random(0L)).toList()
        // 3 must come first; remaining is base's order minus 3.
        assertEquals(listOf(3, 0, 1, 2, 4), values)
    }

    @Test
    fun `saved bool polarity is tried first`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val guided = SolutionGuided(IndomainMin)
        // IndomainMin on bool: false then true → 0, 1.
        guided.onSolution(Sample(booleanArrayOf(true), IntArray(0)))
        val values = guided.values(session, VarRef.Bool(0), Random(0L)).toList()
        assertEquals(listOf(1, 0), values, "saved true (=1) must be tried first")
    }

    @Test
    fun `when saved value is no longer in domain fall through to base`() {
        // v0 ∈ [2, 4]; saved v0 = 0 is out of domain; expect IndomainMin order verbatim.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(2, 4)),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val guided = SolutionGuided(IndomainMin)
        guided.onSolution(Sample(BooleanArray(0), intArrayOf(0)))
        val values = guided.values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals(listOf(2, 3, 4), values)
    }

    @Test
    fun `engine still solves with solution-guided wrapper`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)),
        )
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(
                variableHeuristic = SmallestDomain,
                valueHeuristic = SolutionGuided(IndomainMin),
                randomSeed = 0L,
            )
        )
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals((0..3).toSet(), sat.assignment.ints.toSet())
    }

    @Test
    fun `solution-guided receives onSolution from engine`() {
        // Track that the heuristic's onSolution is called via a recording wrapper.
        val recorded = ArrayList<Sample>()
        val spy = object : ValueHeuristic {
            override fun values(session: PropagationSession, varRef: VarRef, rng: Random) =
                IndomainMin.values(session, varRef, rng)
            override fun onSolution(snapshot: Sample) { recorded.add(snapshot) }
        }
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            factors = emptyArray(),
        )
        BacktrackSolver(problem).enumerate(
            BacktrackParams(
                valueHeuristic = spy,
                randomSeed = 0L,
            )
        ).toList()
        assertTrue(recorded.isNotEmpty(), "spy should have received at least one onSolution call")
        assertEquals(4, recorded.size, "expected 4 SAT leaves (2^2); got ${recorded.size}")
    }
}
