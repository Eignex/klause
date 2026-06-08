package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KnapsackTest {

    @Test
    fun `knapsack with binary xs maintains sums`() {
        // 3 items, binary selection. weights = [2, 3, 5], profits = [3, 4, 8].
        // capacity w ∈ [0, 5] forces at most one item w ≤ 5 — best is item 3 with profit 8.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 5),
                IntDomain(0, 100),
            ),
            factors = arrayOf<Factor>(
                Knapsack(
                    weights = intArrayOf(2, 3, 5),
                    profits = intArrayOf(3, 4, 8),
                    xs = intArrayOf(0, 1, 2),
                    w = 3,
                    p = 4,
                ),
            ),
        )
        val r = BacktrackSolver(problem).minimize(
            LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 0L, -1L)),
            BacktrackParams(randomSeed = 0L),
        )
        val optimal = assertIs<MinimizeResult.Optimal>(r)
        // Best profit = 8 (item 3 alone). Objective = -profit.
        assertEquals(-8.0, optimal.objectiveValue)
    }

    @Test
    fun `knapsack with all items + tight capacity gives Sat`() {
        // Force w = 5, p = 8 via singleton item picks (item 0 = 0, item 1 = 0, item 2 = 1).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0, 0),
                IntDomain(0, 0),
                IntDomain(1, 1),
                IntDomain(0, 10),
                IntDomain(0, 100),
            ),
            factors = arrayOf<Factor>(
                Knapsack(
                    weights = intArrayOf(2, 3, 5),
                    profits = intArrayOf(3, 4, 8),
                    xs = intArrayOf(0, 1, 2),
                    w = 3,
                    p = 4,
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(5, sat.assignment.ints[3])
        assertEquals(8, sat.assignment.ints[4])
    }
}
