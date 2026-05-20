package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImprovementsTest {

    @Test
    fun `improvements yields strictly decreasing objectives then a terminal Optimal`() {
        // Single int var in [0, 5] with no constraints; minimising it. With IndomainMax
        // the DFS tries 5 first (objective 5) → BestFound(5) → backtrack → 4 → BestFound(4)
        // → ... → 0 → BestFound(0) → no more values → Optimal(0). 6 yields total.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = doubleArrayOf(1.0))
        val seq = BacktrackSolver(problem).improvements(obj, BacktrackParams(
            randomSeed = 0L,
            variableHeuristic = InputOrder,
            valueHeuristic = IndomainMax,
        )).toList()
        // Last yield is the terminal verdict.
        val terminal = seq.last()
        assertIs<MinimizeResult.Optimal>(terminal)
        assertEquals(0.0, terminal.objective)
        // Earlier yields are BestFound carrying strictly-decreasing objectives.
        val earlier = seq.dropLast(1)
        assertTrue(earlier.isNotEmpty(), "expected at least one intermediate improvement")
        var prev = Double.POSITIVE_INFINITY
        for (m in earlier) {
            val bf = assertIs<MinimizeResult.BestFound>(m)
            assertTrue(bf.objective < prev, "improvements must strictly decrease; ${bf.objective} after $prev")
            prev = bf.objective
        }
    }

    @Test
    fun `improvements on an infeasible problem yields a single Infeasible`() {
        // Single int var [3, 5], constraint forces it ≤ 1 → Unsat.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(IntDomain(3, 5)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)),
        )
        val obj = LinearObjective(intCoefficients = doubleArrayOf(1.0))
        val seq = BacktrackSolver(problem).improvements(obj, BacktrackParams(randomSeed = 0L)).toList()
        assertEquals(1, seq.size)
        assertIs<MinimizeResult.Infeasible>(seq[0])
    }

    @Test
    fun `minimize equals improvements last`() {
        // Contract: minimize is improvements().last(). Verify on a small problem.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 7)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = doubleArrayOf(1.0))
        val solver = BacktrackSolver(problem)
        val params = BacktrackParams(randomSeed = 0L, variableHeuristic = InputOrder, valueHeuristic = IndomainMin)
        val viaMinimize = solver.minimize(obj, params)
        val viaImprovements = solver.improvements(obj, params).last()
        assertEquals(viaMinimize, viaImprovements)
    }
}
