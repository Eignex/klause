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

class RegularTest {

    @Test
    fun `regular accepts strings matching the DFA`() {
        // DFA: alphabet = {1, 2}; states = {1, 2}; q0 = 1, F = {2}.
        // δ(1, 1) = 1, δ(1, 2) = 2, δ(2, 1) = 1, δ(2, 2) = 2.
        // Accepts strings ending in 2. 4-length seq ∈ {1, 2}^4.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(
                Regular(
                    seq = intArrayOf(0, 1, 2, 3),
                    numStates = 2,
                    alphabetSize = 2,
                    // T[(q-1)*S + (s-1)] :  (1,1)→1, (1,2)→2, (2,1)→1, (2,2)→2
                    transitions = intArrayOf(1, 2, 1, 2),
                    q0 = 1,
                    accepting = intArrayOf(2),
                ),
            ),
        )
        // Every accepted string must end in 2.
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(20).forEach { sample ->
            assertEquals(2, sample.ints[3], "regular violated: ints=${sample.ints.toList()}")
        }
    }

    @Test
    fun `regular rejects pinned-to-fail strings`() {
        // Same DFA. Pin seq = (1, 1, 1, 1) → ends in state 1 ∉ F. Unsat.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 1) },
            factors = arrayOf<Factor>(
                Regular(
                    seq = intArrayOf(0, 1, 2, 3),
                    numStates = 2,
                    alphabetSize = 2,
                    transitions = intArrayOf(1, 2, 1, 2),
                    q0 = 1,
                    accepting = intArrayOf(2),
                ),
            ),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
