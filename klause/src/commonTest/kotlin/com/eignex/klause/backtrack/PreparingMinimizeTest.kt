package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparingMinimizeTest {
    @Test
    fun `a preparation time boundary publishes no incumbent before resuming`() {
        val fixture = FiniteCutoffKnapsackFixture
        val samples = mutableListOf<MinimizeResult.WithSample>()
        PreparingMinimize(BacktrackSolver(fixture.problem), fixture.objective, fixture.params).use { search ->
            val paused = search.runSlice(Cancellation.Never, 0L, 100L, samples::add)

            assertNull(paused)
            assertTrue(search.preparationPending)
            assertTrue(samples.isEmpty())
            val result = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L, samples::add),
            )
            assertEquals(-4.0, result.objective)
            assertFalse(search.preparationPending)
        }
    }

    @Test
    fun `preparation slices preserve the optimum and propagation work`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 1)),
            Array(2048) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1) },
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L, lpPlan = LpPlan())
        val solver = BacktrackSolver(problem)
        val whole = solver.resumable(objective, params).use {
            assertIs<MinimizeResult.Optimal>(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {})
        }
        solver.resumable(objective, params).use { search ->
            var terminal: MinimizeResult? = null
            var preparationPauses = 0
            var slices = 0
            while (terminal == null && slices++ < 100) {
                val before = search.work
                terminal = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L) {}
                assertTrue(search.work >= before)
                if (search.preparationPending && search.work > 0L) preparationPauses++
            }

            assertTrue(preparationPauses > 0)
            assertEquals(whole.objective, assertIs<MinimizeResult.Optimal>(terminal).objective)
            assertEquals(whole.stats.search.propagationWork, search.stats.search.propagationWork)
        }
    }
}
