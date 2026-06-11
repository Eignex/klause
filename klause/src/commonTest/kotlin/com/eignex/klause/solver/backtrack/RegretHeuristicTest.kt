package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegretHeuristicTest {

    @Test
    fun `MaxRegret picks variable with largest weighted span`() {
        // 3 int vars, all unpinned, domain widths and coefficients chosen so v1 wins.
        //   v0: dom [0..4] (width 4), coeff 1 → regret 4
        //   v1: dom [0..3] (width 3), coeff 5 → regret 15
        //   v2: dom [0..9] (width 9), coeff 0 → regret 0
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 3), IntDomain(0, 9)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 5L, 0L))
        val session = PropagationSession(problem)
        val picked = MaxRegret(obj).pick(session, Random(0L))
        assertEquals(VarRef.IntVar(1), picked)
    }

    @Test
    fun `MaxRegret falls through to base when all regrets are zero`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            factors = emptyArray(),
        )
        // Zero coefficients → all regrets 0; base = InputOrder returns v0.
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L))
        val session = PropagationSession(problem)
        val picked = MaxRegret(obj, base = InputOrder).pick(session, Random(0L))
        assertEquals(VarRef.IntVar(0), picked)
    }

    @Test
    fun `IndomainBest descending for negative coefficient`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L)) // maximise → try high first
        val session = PropagationSession(problem)
        val values = IndomainBest(obj).values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals(listOf(4, 3, 2, 1, 0), values)
    }

    @Test
    fun `IndomainBest ascending for positive coefficient`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(2L))
        val session = PropagationSession(problem)
        val values = IndomainBest(obj).values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals(listOf(0, 1, 2, 3, 4), values)
    }

    @Test
    fun `MaxRegret + IndomainBest solve a minimisation cleanly`() {
        // minimize x + 2y subject to x + y >= 3, x ∈ [0..5], y ∈ [0..5].
        // Optimal: x = 3, y = 0, obj = 3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.GE,
                    bound = 3,
                ),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val r = BacktrackSolver(problem).minimize(
            obj,
            BacktrackParams(
                variableHeuristic = MaxRegret(obj),
                valueHeuristic = IndomainBest(obj),
                randomSeed = 0L,
            ),
        )
        val opt = assertIs<MinimizeResult.Optimal>(r)
        assertEquals(3.0, opt.objectiveValue)
        assertEquals(3, opt.sample.ints[0])
        assertEquals(0, opt.sample.ints[1])
    }
}
