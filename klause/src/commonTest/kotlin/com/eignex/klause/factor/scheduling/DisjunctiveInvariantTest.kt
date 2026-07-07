package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.scheduling.Disjunctive
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DisjunctiveInvariantTest {

    private fun threeUnitTasks(): Problem {
        val factor = Disjunctive(starts = intArrayOf(0, 1, 2), durations = intArrayOf(1, 1, 1))
        return Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `non-overlapping schedule satisfies disjunctive`() {
        val problem = threeUnitTasks()
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.recompute()
        assertEquals(0, state.cost)
    }

    @Test
    fun `overlap counts as violation`() {
        val problem = threeUnitTasks()
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 2)
        state.recompute()
        assertTrue(state.cost > 0, "tasks at the same time should violate disjunctive")
    }

    @Test
    fun `LS finds a feasible disjunctive schedule`() {
        val problem = threeUnitTasks()
        val solver = LocalSearchSolver(
            problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 17L)).assignment
        assertNotNull(sample)
        val starts = sample.ints
        val occ = BooleanArray(3)
        for (i in 0 until 3) {
            val slot = starts[i].toInt()
            assertTrue(!occ[slot], "double-booked at slot $slot in ${starts.toList()}")
            occ[slot] = true
        }
    }
}
