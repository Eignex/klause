package com.eignex.klause.solver.factor

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test

/**
 * Structured-move feasibility-preservation for factors whose `proposeRepairMoves` is
 * approximate by design (so the full [AllFactorsOracleTest] repair-cover oracle would reject
 * them), or whose oracle instance there doesn't exercise the implicit neighbourhood. Runs only
 * [MoveSetOracle.assertStructuredMovesPreserveFeasibility]: from feasible starts, every
 * structured move must keep the factor satisfied.
 */
class StructuredMoveOracleTest {

    private fun oneFactor(factor: com.eignex.klause.solver.Factor, intDomains: Array<IntDomain>) {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = intDomains.size,
            intDomains = intDomains,
            factors = listOf(factor),
        )
        MoveSetOracle.assertStructuredMovesPreserveFeasibility(problem, factor::class.simpleName ?: "factor", iters = 60)
    }

    @Test fun `equal-duration disjunctive start-swaps preserve no-overlap`() {
        oneFactor(
            Disjunctive(starts = intArrayOf(0, 1, 2), durations = intArrayOf(2, 2, 2)),
            arrayOf(IntDomain(0, 6), IntDomain(0, 6), IntDomain(0, 6)),
        )
    }
}
