package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.meta.coreguided.CoreRefinementMode
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@RunWith(Parameterized::class)
class CoreGuidedWorkerStratificationTest(
    private val stratified: Boolean,
    private val refinement: CoreRefinementMode,
) {
    @Test
    fun `selected weight strata should retain their exact floor across cancellation`() {
        val label = if (stratified) "core-guided-stratified" else "core-guided"
        val problem = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
        )).bake()
        val objective = LinearObjective(longArrayOf(1, 100, 100))
        var cancelled = false
        val floors = mutableListOf<Double>()
        val recipe = BacktrackCatalog.byLabel(label).editing {
            it.copy(nativeSat = false, objectiveLowerBoundSink = { value ->
                floors += value
                cancelled = true
            })
        }
        val worker = BacktrackWorkerConfig(recipe).withCoreRefinement(refinement).materialize(
            problem, 0, 0, 3L, 1.0, objective, null, null, null, null,
        )

        worker.use {
            requireNotNull(worker.newResumableSearch({ Double.POSITIVE_INFINITY }, Cancellation.Never)).use { handle ->
                val paused = handle.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1L) {
                    error("unfinished first query")
                }

                val first = if (stratified || refinement == CoreRefinementMode.REASONS) 100L else 1L
                assertNull(paused)
                assertEquals(listOf(first.toDouble()), floors)
                assertEquals(first.toString(), handle.stats.coreGuided.lowerBound.toString())
                assertEquals("bt/$label", worker.label)
                cancelled = false
                val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {}
                assertEquals(100L, objective.evaluateLong(assertIs<MinimizeResult.Optimal>(result).sample))
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "stratified={0}, refinement={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { stratified ->
            listOf(CoreRefinementMode.NONE, CoreRefinementMode.REASONS).map { refinement ->
                arrayOf<Any>(stratified, refinement)
            }
        }
    }
}
