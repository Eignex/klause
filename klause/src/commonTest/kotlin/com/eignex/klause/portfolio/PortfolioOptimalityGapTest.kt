package com.eignex.klause.portfolio

import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.solver.GapBoundedSearch
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.OptimalityGap
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.TestTimeSource

class PortfolioOptimalityGapTest {
    private val gap = OptimalityGap(
        OptimalityTolerance(relative = SemanticRational.of(bigIntOf(1), bigIntOf(10_000))), integral = true,
    )

    @Test
    fun `a published exact floor should close its accepted incumbent without a positive gap`() {
        val bounds = SharedObjectiveBound()
        val arm = GapScript(listOf(2.0), null, Double.POSITIVE_INFINITY) { bounds.publish(2.0) }

        val result = run(arm, ExecutionControl { arm.slices >= 4 }, bounds, LinearObjective(constant = 2L), null)

        assertIs<MinimizeResult.Optimal>(result)
    }

    @Test
    fun `a rounded down incumbent should not meet a lower exact source floor`() {
        val bounds = SharedObjectiveBound()
        val arm = GapScript(listOf(9_007_199_254_740_992.0), null, Double.POSITIVE_INFINITY) {
            bounds.publish(9_007_199_254_740_992.0)
        }

        val result = run(arm, ExecutionControl { arm.slices >= 4 }, bounds,
            LinearObjective(constant = 9_007_199_254_740_993L), null)

        assertIs<MinimizeResult.BestFound>(result)
    }

    @Test
    fun `a scalar match without exact objective capture should not close the source`() {
        val bounds = SharedObjectiveBound()
        val arm = GapScript(listOf(2.0), null, Double.POSITIVE_INFINITY) { bounds.publish(2.0) }

        val result = run(arm, ExecutionControl { arm.slices >= 4 }, bounds, tolerance = null)

        assertIs<MinimizeResult.BestFound>(result)
    }

    @Test
    fun `gap scoped facts should not establish an exact source closure`() {
        val bounds = SharedObjectiveBound()
        val arm = GapScript(listOf(2.0), null, 1.0) { bounds.publish(2.0) }

        val result = run(arm, ExecutionControl { arm.slices >= 4 }, bounds, LinearObjective(constant = 2L), null)

        assertIs<MinimizeResult.BestFound>(result)
    }

    @Test
    fun `an arm ahead of the pool should be credited only the bound its own search proved`() {
        // The pool reads 1,000,100 while the arm prunes under the gap cutoff of its own 1,000,050.
        val arm = GapScript(listOf(1_000_100.0), MinimizeResult.BestFound(EMPTY, 1_000_050.0,
            TerminationReason.SearchExhausted), gap.cutoff(1_000_050.0))

        val result = assertIs<MinimizeResult.Optimal>(run(arm, ExecutionControl.Never))

        assertEquals(1_000_050.0, result.objective)
        assertEquals(999_950.0, result.gapBound)
    }

    @Test
    fun `a published bound within the gap of the pooled incumbent should end the run with that bound`() {
        val bounds = SharedObjectiveBound()
        val arm = GapScript(listOf(1_000_050.0), null, Double.POSITIVE_INFINITY) { bounds.publish(999_990.0) }

        val result = assertIs<MinimizeResult.Optimal>(run(arm, ExecutionControl.Never, bounds))

        assertEquals(999_990.0, result.gapBound)
    }

    @Test
    fun `an unpublished bound should never end the run`() {
        val arm = GapScript(listOf(1_000_050.0), null, Double.POSITIVE_INFINITY)

        val result = run(arm, ExecutionControl { arm.slices >= 4 }, SharedObjectiveBound())

        assertIs<MinimizeResult.BestFound>(result)
    }

    private fun run(
        arm: GapScript,
        stop: ExecutionControl,
        bounds: SharedObjectiveBound? = null,
        exactObjective: LinearObjective? = null,
        tolerance: OptimalityGap? = gap,
    ): MinimizeResult {
        val worker = trackingWorker("gap", 0, arm).also {
            it.optimalityGap = tolerance
            if (bounds != null) it.sharedPools = SharedPools(null, null, bounds)
        }
        return Portfolio.thompson(listOf(worker), seed = 0, reseedStaleThreshold = 0).also {
            it.runTimeSource = TestTimeSource()
            it.exactLinearObjective = exactObjective
        }.use { it.minimize(stop) }
    }

    // Publishes each scripted incumbent on its own slice, then ends with the terminal, if any.
    private class GapScript(
        private val incumbents: List<Double>,
        private val terminal: MinimizeResult?,
        override val gapFloor: Double,
        private val onSlice: () -> Unit = {},
    ) : ResumableSearch, GapBoundedSearch {
        var slices = 0
        override val appliedOptimalityGap: Boolean get() = gapFloor.isFinite()
        override val exhaustionBound: Double
            get() = if (slices > incumbents.size) gapFloor else Double.NEGATIVE_INFINITY

        override fun runSlice(
            global: ExecutionControl,
            sliceMillis: Long,
            sliceNodes: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit,
        ): MinimizeResult? {
            onSlice()
            val slice = slices++
            if (slice < incumbents.size) {
                onIncumbent(MinimizeResult.BestFound(EMPTY, incumbents[slice], TerminationReason.BudgetExhausted))
                return null
            }
            return terminal
        }

        override val isDone: Boolean get() = terminal != null && slices > incumbents.size
        override val stats: SolveStats get() = SolveStats.EMPTY
        override val work: Long get() = slices.toLong()
        override fun close() = Unit
    }

    private companion object {
        val EMPTY = Sample(BooleanArray(0), LongArray(0))
    }
}
