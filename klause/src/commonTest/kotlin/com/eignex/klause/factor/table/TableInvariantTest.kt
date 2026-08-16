package com.eignex.klause.factor.table

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableInvariantTest {

    @Test
    fun `satisfied when assignment is in the table`() {
        // tuples = [(0,1), (2,3)]; assignment (2,3) is in the table.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 1, 2, 3)),
            ),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 3)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violated when assignment is not in the table`() {
        // tuples = [(0,1), (2,3)]; assignment (1,1) is not in the table.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 1, 2, 3)),
            ),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertTrue(state.factors[0].violationDegree(state, 0) > 0)
    }

    @Test
    fun `delta is negative when move brings assignment closer to a tuple`() {
        // Violated: (1,1). Closest tuple is (0,1) or (2,3) at distance 1.
        // Setting x0=0 brings us to (0,1) → distance 0 → delta < 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 1, 2, 3)),
            ),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val delta = state.factors[0].deltaIfIntSet(state, 0, intVar = 0, newValue = 0)
        assertTrue(delta < 0, "move to tuple should reduce violation; delta=$delta")
    }

    @Test
    fun `ls solver finds only allowed tuples`() {
        // tuples = [(1,2,3), (4,5,6)].
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(1, 9) },
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1, 2), tuples = longArrayOf(1, 2, 3, 4, 5, 6)),
            ),
        )
        val solver = LocalSearchSolver(problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 3_000, randomSeed = 1)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        val allowed = setOf(listOf(1, 2, 3), listOf(4, 5, 6))
        for (s in samples) {
            assertTrue(
                s.ints.map { it.toInt() } in allowed,
                "assignment ${s.ints.toList()} not in table",
            )
        }
    }
}
