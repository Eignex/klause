package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.SolveStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LpEpochStatsTest {
    @Test
    fun `production epoch opportunities reach the CLI statistics stream`() {
        val problem = Problem(1, 2, Array(2) { IntDomain(0, 4) },
            arrayOf(ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)))
        val result = assertIs<SolveResult.Sat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(lpEpochs = true)))

        val printed = lpStatPairs(result.stats).toMap()

        assertEquals("1", printed["lp_epoch_root_opportunities"])
    }

    @Test
    fun `shared epoch counters render even when no ordinary LP solve is counted`() {
        val stats = SolveStats(lp = LpStats(epochs = mapOf("shared_unchanged" to 2L, "shared_total_ns" to 91L)))

        val printed = lpStatPairs(stats).filter { it.first.startsWith("lp_epoch_") }

        assertEquals(listOf("lp_epoch_shared_total_ns" to "91", "lp_epoch_shared_unchanged" to "2"), printed)
        assertTrue(lpStatPairs(SolveStats()).isEmpty())
    }
}
