package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Factor-level propagation coverage for [Cumulatives] (part of #104): per-machine
 * time-tabling tightening, plus the single-task / zero-duration / zero-capacity edge cases.
 * Var layout per test is documented inline; bounds/durations/resources are constants here.
 */
class CumulativesFactorTest {

    @Test
    fun `per-machine time-tabling shaves a start that would overload the machine`() {
        // Two tasks (dur 2, res 2) both pinned to machine 0 (capacity 2). Task 0 is pinned to
        // start 0, so its mandatory part [0,2) already fills machine 0. Task 1 (free start)
        // therefore cannot overlap [0,2): its start must be pushed to ≥ 2.
        val factor = Cumulatives(
            starts = intArrayOf(0, 1),
            durations = intArrayOf(2, 2),
            resources = intArrayOf(2, 2),
            machines = intArrayOf(2, 3),
            bounds = intArrayOf(2, 2),
            upper = true,
            minMachine = 0,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 0), // task 0 start pinned
                IntDomain(0, 3), // task 1 start free
                IntDomain(0, 0), // task 0 → machine 0
                IntDomain(0, 0), // task 1 → machine 0
            ),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.baked
        val implied = assertIs<PropagationResult.Implied>(r)
        assertEquals(2, implied.intMinOrNullCompat(1), "task 1 start must be pushed past machine 0's mandatory part")
    }

    @Test
    fun `single task never overloads its machine`() {
        // One task, dur 2 res 1, on a capacity-1 machine — fits trivially, no false failure.
        val factor = Cumulatives(
            starts = intArrayOf(0),
            durations = intArrayOf(2),
            resources = intArrayOf(1),
            machines = intArrayOf(1),
            bounds = intArrayOf(1),
            upper = true,
            minMachine = 0,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 0)),
            factors = arrayOf<Factor>(factor),
        )
        assertIs<PropagationResult.Implied>(problem.baked)
    }

    @Test
    fun `zero-duration task contributes nothing even on a zero-capacity machine`() {
        // A duration-0 task occupies no time, so it never loads the machine — feasible even
        // when its resource demand exceeds the (zero) capacity.
        val factor = Cumulatives(
            starts = intArrayOf(0),
            durations = intArrayOf(0),
            resources = intArrayOf(5),
            machines = intArrayOf(1),
            bounds = intArrayOf(0),
            upper = true,
            minMachine = 0,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 0)),
            factors = arrayOf<Factor>(factor),
        )
        assertIs<PropagationResult.Implied>(problem.baked)
    }

    @Test
    fun `zero-capacity machine with a positive pinned task is infeasible`() {
        // Task pinned to start 0 (mandatory part [0,1)), res 1, on a capacity-0 machine →
        // the mandatory profile overloads at t=0.
        val factor = Cumulatives(
            starts = intArrayOf(0),
            durations = intArrayOf(1),
            resources = intArrayOf(1),
            machines = intArrayOf(1),
            bounds = intArrayOf(0),
            upper = true,
            minMachine = 0,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 0)),
            factors = arrayOf<Factor>(factor),
        )
        assertIs<PropagationResult.Unsat>(problem.baked)
    }
}
