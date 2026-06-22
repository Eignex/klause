package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.table.Regular
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegularInvariantTest {

    // DFA: alphabet {1,2}, states {1,2}, q0=1, F={2}.
    // δ(1,1)=1, δ(1,2)=2, δ(2,1)=1, δ(2,2)=2. Accepts strings ending in 2.
    private val transitions = intArrayOf(1, 2, 1, 2)

    private fun endsWith2Factor(n: Int): Factor = Regular(
        seq = IntArray(n) { it },
        numStates = 2,
        alphabetSize = 2,
        transitions = transitions,
        q0 = 1,
        accepting = intArrayOf(2),
    )

    @Test
    fun `satisfied when sequence matches the DFA`() {
        // 3-symbol sequence ending in 2 (e.g. 1,1,2) → accepted.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(endsWith2Factor(3)),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violated when sequence does not match the DFA`() {
        // 3-symbol sequence not ending in 2 (e.g. 1,2,1) → rejected.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(endsWith2Factor(3)),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 1) // ends in 1, not accepted
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertTrue(state.factors[0].violationDegree(state, 0) > 0)
    }

    @Test
    fun `delta is negative when move causes acceptance`() {
        // Violated (ends in 1). Setting last symbol to 2 should fix it → delta < 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(endsWith2Factor(3)),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 1)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val delta = state.factors[0].deltaIfIntSet(state, 0, intVar = 2, newValue = 2)
        assertTrue(delta < 0, "setting last symbol to 2 should improve; delta=$delta")
    }

    @Test
    fun `ls solver produces only accepted sequences`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(endsWith2Factor(4)),
        )
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 3_000, randomSeed = 7)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            assertEquals(2, s.ints[3], "sequence must end in 2; got ${s.ints.toList()}")
        }
    }
}
