package com.eignex.klause.backtrack

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResumableMinimizeTest {
    @Test
    fun `carried incumbent must satisfy replacement assumptions and deductions`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        for (assumptions in listOf(Assumptions.None.withInt(0, 3L), Assumptions.None.withTightenedMin(0, 2L))) {
            val params = BacktrackParams(randomSeed = 0L)
            val first = ResumableMinimize(BacktrackSolver(problem), objective, params)
            assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
            first.replacingObjective(objective, params.copy(assumptions = assumptions)).use { second ->
                val offered = ArrayList<Long>()
                val result = assertIs<MinimizeResult.Optimal>(
                    second.runSlice(Cancellation.Never, 1000L, 256L) {
                    offered += it.sample.ints[0]
                }
                )
                assertTrue(offered.all { it >= 2L })
                assertEquals(if (assumptions.numInts > 0) 3L else 2L, result.sample.ints[0])
                second.replacingObjective(objective, params).use { third ->
                    val restored = assertIs<MinimizeResult.Optimal>(third.runSlice(Cancellation.Never, 1000L, 256L) {})
                    assertEquals(0L, restored.sample.ints[0])
                }
            }
        }
    }

    @Test
    fun `contradictory replacement deductions discard a carried incumbent`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        val first = ResumableMinimize(BacktrackSolver(problem), objective, params)
        assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
        first.replacingObjective(
            objective,
            params.copy(assumptions = Assumptions.None.withTightenedMin(0, 4)),
        ).use { second ->
            var offers = 0
            assertIs<MinimizeResult.Infeasible>(second.runSlice(Cancellation.Never, 1000L, 256L) { offers++ })
            assertEquals(0, offers)
        }
    }

    @Test
    fun `replacement can remove an earlier boolean root assumption`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(boolWeights = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        val first = ResumableMinimize(BacktrackSolver(problem), objective, params)
        first.replacingObjective(
            objective,
            params.copy(assumptions = Assumptions.None.withBool(0, true)),
        ).use { second ->
            val restricted = assertIs<MinimizeResult.Optimal>(second.runSlice(Cancellation.Never, 1000L, 256L) {})
            assertEquals(true, restricted.sample.bools[0])
            second.replacingObjective(objective, params).use { third ->
                val restored = assertIs<MinimizeResult.Optimal>(third.runSlice(Cancellation.Never, 1000L, 256L) {})
                assertEquals(false, restored.sample.bools[0])
            }
        }
    }

    @Test
    fun `cancelled replacement leaves the original search usable`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        for (initiallyCancelled in listOf(true, false)) {
            var cancelled = initiallyCancelled
            val exchange = object : ClauseExchange {
                override fun onRestart(session: PropagationSession) = Unit
                override fun onSearchStart(session: PropagationSession) {
                    cancelled = true
                }
            }
            ResumableMinimize(BacktrackSolver(problem), objective, params).use { first ->
                assertFailsWith<CancellationException> {
                    first.replacingObjective(
                        objective,
                        params.copy(cancellation = Cancellation { cancelled }, clauseExchange = exchange),
                    )
                }
                val result = assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
                assertEquals(0L, result.sample.ints[0])
            }
        }
    }
}
