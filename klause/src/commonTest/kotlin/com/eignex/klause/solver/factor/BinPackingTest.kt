package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BinPackingTest {

    @Test
    fun `uniform capacity rejects overpacking`() {
        // 3 items of weight 4 each. 2 bins of capacity 5 each. Forces 1 per bin (impossible
        // for 3 items into 2 bins under cap 5) → Unsat.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(
                BinPacking(
                    bins = intArrayOf(0, 1, 2),
                    weights = intArrayOf(4, 4, 4),
                    mode = BinPacking.Mode.UniformCapacity,
                    uniformCapacity = 5,
                    numBins = 2,
                ),
            ),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `per-bin capacity packs respecting limits`() {
        // 4 items: weights 3, 2, 2, 3. 2 bins: capacities 5, 5. Feasible.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(
                BinPacking(
                    bins = intArrayOf(0, 1, 2, 3),
                    weights = intArrayOf(3, 2, 2, 3),
                    mode = BinPacking.Mode.PerBinCapacity,
                    capacities = intArrayOf(5, 5),
                    numBins = 2,
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        // Validate: per-bin load ≤ 5.
        val loads = IntArray(2)
        for (i in 0..3) loads[sat.assignment.ints[i] - 1] += listOf(3, 2, 2, 3)[i]
        for (b in 0..1) assertTrue(loads[b] <= 5, "bin $b overload: $loads")
    }

    @Test
    fun `load vars track per-bin sum`() {
        // 3 items of weight 2 each, all → bin 1 (pinned). load[0] must = 6.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(1, 1),
                IntDomain(1, 1),
                IntDomain(1, 1),
                IntDomain(0, 10),
                IntDomain(0, 10),
            ),
            factors = arrayOf<Factor>(
                BinPacking(
                    bins = intArrayOf(0, 1, 2),
                    weights = intArrayOf(2, 2, 2),
                    mode = BinPacking.Mode.LoadVars,
                    loadVars = intArrayOf(3, 4),
                    numBins = 2,
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(6, sat.assignment.ints[3], "load[0] should equal 6")
        assertEquals(0, sat.assignment.ints[4], "load[1] should equal 0")
    }
}
