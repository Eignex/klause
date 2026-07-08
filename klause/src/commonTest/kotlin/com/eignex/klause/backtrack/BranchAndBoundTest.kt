package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.scheduling.Disjunctive
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BranchAndBoundTest {

    @Test
    fun `minimize finds the all-zeros assignment under a positive linear objective`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val obj = LinearObjective(boolWeights = longArrayOf(1L, 2L, 3L, 4L))
        val sample = BacktrackSolver(problem).minimize(obj, BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        for (i in 0 until 4) assertEquals(false, sample.bools[i])
    }

    @Test
    fun `minimize picks the cheapest int-var value under a positive coefficient`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(2, 9)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))
        val sample = BacktrackSolver(problem).minimize(obj, BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        assertEquals(2, sample.ints[0])
    }

    @Test
    fun `minimize picks the highest int-var value under a negative coefficient`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 7)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L))
        val sample = BacktrackSolver(problem).minimize(obj, BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        assertEquals(7, sample.ints[0])
    }

    @Test
    fun `branch-and-bound prunes — minimizing within a budget that full enumeration would blow`() {
        // 16 independent bool vars: 2^16 = 65,536 leaves. Full enumeration would exceed
        // a 200-decision budget. With B&B + a positive linear objective + value-ordering
        // that prefers false first (the smallest contribution), the very first leaf
        // is the all-false optimum; the bound predicate then rejects any subsequent
        // pin that would raise the objective above zero. Use deterministic heuristics
        // (InputOrder + IndomainMin) so the demonstration is reproducible.
        val n = 16
        val problem = Problem(
            numBoolVars = n,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val obj = LinearObjective(boolWeights = LongArray(n) { (it + 1).toLong() })
        val sample = BacktrackSolver(problem).minimize(
            obj,
            BacktrackParams(
                randomSeed = 0L,
                maxDecisions = 200L,
                variableSelector = InputOrder,
                valueSelector = IndomainMin,
            ),
        ).assignment
        assertNotNull(sample, "B&B should land at the optimum well inside a 200-decision budget")
        for (i in 0 until n) {
            assertEquals(
                false,
                sample.bools[i],
                "B&B optimum is all-false; got ${sample.bools.toList()}",
            )
        }
    }

    @Test
    fun `branch-and-bound minimizes makespan of three disjunctive tasks`() {
        // Three tasks of duration 2 on a single machine; start dom [0, 4]. Minimum
        // makespan (max end time) is 6 with starts {0, 2, 4} or permutations. Since
        // LinearObjective doesn't directly express max-of-endings, we approximate
        // with the sum of starts — under disjunctive's no-overlap, the minimum total
        // start time is 0 + 2 + 4 = 6 (any non-overlapping schedule has the same
        // sum, so any feasible is optimal).
        val starts = intArrayOf(0, 1, 2)
        val durations = longArrayOf(2, 2, 2)
        val factor = Disjunctive(starts = starts, durations = durations)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
        val sample = BacktrackSolver(problem).minimize(obj, BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        val sum = sample.ints[0] + sample.ints[1] + sample.ints[2]
        assertEquals(6, sum, "optimal disjunctive schedule sums to 0+2+4=6; got ${sample.ints.toList()}")
        val occ = IntArray(8)
        for (i in 0 until 3) {
            val s = sample.ints[i].toInt()
            for (t in s until s + 2) if (t in occ.indices) occ[t]++
        }
        for (t in occ.indices) assertTrue(occ[t] <= 1, "disjunctive violated at t=$t")
    }
}
