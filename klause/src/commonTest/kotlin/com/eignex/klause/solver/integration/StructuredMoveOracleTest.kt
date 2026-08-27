package com.eignex.klause.solver.integration

import com.eignex.klause.factor.MoveSetOracle
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
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

    private fun oneFactor(factor: Factor, intDomains: Array<IntDomain>) {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = intDomains.size,
            intDomains = intDomains,
            factors = listOf(factor),
        )
        MoveSetOracle.assertStructuredMovesPreserveFeasibility(
            problem,
            factor::class.simpleName ?: "factor",
            iters = 60,
        )
    }

    @Test fun `equal-duration disjunctive start-swaps preserve no-overlap`() {
        oneFactor(
            Cumulative.unary(starts = intArrayOf(0, 1, 2), durations = longArrayOf(2, 2, 2)),
            arrayOf(IntDomain(0, 6), IntDomain(0, 6), IntDomain(0, 6)),
        )
    }

    @Test fun `equal-footprint diffn position-swaps preserve non-overlap`() {
        // Two 1x1 rectangles (identical footprint): the position swap fires and must keep them
        // non-overlapping.
        oneFactor(
            Diffn(
                xs = intArrayOf(0, 1),
                ys = intArrayOf(2, 3),
                widths = longArrayOf(1, 1),
                heights = longArrayOf(1, 1),
            ),
            arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
        )
    }
}
