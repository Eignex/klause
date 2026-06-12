package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.selector.IndomainMin
import com.eignex.klause.solver.backtrack.selector.InputOrder
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LubyRestartTest {

    @Test
    fun `restart-enabled search still finds a satisfying assignment`() {
        // Trivial 4-bool problem with exactly-one constraint — 4 distinct feasibles.
        // The restart loop should land on one of them.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val params = BacktrackParams(
            randomSeed = 0L,
            lubyRestartBase = 4L,
            phaseSaving = true,
            variableHeuristic = InputOrder,
            valueHeuristic = IndomainMin,
        )
        val r = BacktrackSolver(problem).solve(params)
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.bools.count { it })
    }

    @Test
    fun `minimize with restarts converges to the optimum`() {
        // 16 independent bools with weights 1..16; optimum is all-false.
        val n = 16
        val problem = Problem(
            numBoolVars = n,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val obj = LinearObjective(boolWeights = LongArray(n) { (it + 1).toLong() })
        val result = BacktrackSolver(problem).minimize(
            obj,
            BacktrackParams(
                randomSeed = 7L,
                lubyRestartBase = 32L,
                phaseSaving = true,
                variableHeuristic = InputOrder,
                valueHeuristic = IndomainMin,
            ),
        )
        // With phase-saving + IndomainMin, the first leaf is the all-false optimum and the
        // B&B bound prunes the rest of the search; verdict should be Optimal once the tree
        // is exhausted.
        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(0.0, optimal.objective)
        for (i in 0 until n) assertEquals(false, optimal.sample.bools[i])
    }

    @Test
    fun `phase-saving preserves the saved value across a backtrack`() {
        // 3 bools, no constraints; 8 feasibles. With IndomainMin + InputOrder + phase
        // saving, the first leaf reached is all-false. After we yield it and backtrack,
        // the saved phase for each var is the value it was committed to in that leaf
        // (false). The next leaf reached should respect the saved phases where the
        // backtrack still permits — DFS toggles only the most recently flipped var.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val params = BacktrackParams(
            randomSeed = 0L,
            phaseSaving = true,
            variableHeuristic = InputOrder,
            valueHeuristic = IndomainMin,
        )
        val samples = BacktrackSolver(problem).enumerate(params).take(2).toList()
        assertEquals(2, samples.size)
        // First leaf with IndomainMin = all false.
        assertEquals(listOf(false, false, false), samples[0].bools.toList())
        // Backtrack flips the last-decided bool (var 2). Phase-saving still has var 0,1
        // recorded as false, so the next leaf should be (false, false, true).
        assertEquals(listOf(false, false, true), samples[1].bools.toList())
    }

    @Test
    fun `unit luby base terminates within the decision budget despite constant restarts`() {
        // lubyRestartBase = 1 restarts on the canonical 1,1,2,... cadence (values asserted in
        // RestartPolicyTest); verify the restart loop still finds SAT under that pressure.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val params = BacktrackParams(
            randomSeed = 0L,
            lubyRestartBase = 1L,
            maxDecisions = 200L,
            variableHeuristic = InputOrder,
            valueHeuristic = IndomainMin,
        )
        val r = BacktrackSolver(problem).solve(params)
        assertIs<SolveResult.Sat>(r)
    }

    @Test
    fun `phase-saving without restarts does not change enumerate output set`() {
        // Phase-saving only re-orders within a DFS — the *set* of yielded models is
        // identical. Verify that on a small instance enumerate returns all 4 models.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val samples = BacktrackSolver(problem).enumerate(
            BacktrackParams(
                randomSeed = 0L,
                phaseSaving = true,
                variableHeuristic = InputOrder,
                valueHeuristic = IndomainMin,
            ),
        ).toList()
        assertEquals(4, samples.size)
        // Each is exactly-one-true.
        for (s in samples) assertEquals(1, s.bools.count { it })
    }
}
