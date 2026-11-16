package com.eignex.klause.lp.engine

import com.eignex.util.numbers.BigFraction
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexArtificialBoundsTest {

    private val zero = ExactLpNumber.of(0L)
    private val nonnegative = ExactLpBounds(lower = ExactLpSide(zero))

    private fun working(
        columns: List<List<Pair<Int, Long>>>,
        rhs: List<Long>,
        bounds: List<ExactLpBounds>,
        costs: List<Long>,
    ): LpModel {
        val model = ExactLpModel(
            columns.map { column -> column.map { (row, value) -> ExactLpEntry(row, ExactLpNumber.of(value)) } },
            rhs.map(ExactLpNumber::of),
            bounds.map(::ExactLpColumn),
            List(rhs.size) { ExactLpRow() },
            ExactLpObjective(costs.map(ExactLpNumber::of)),
        )
        return assertNotNull(LpExactState(model).toWorkingModel())
    }

    @Test
    fun `a dual infeasible slack start should reach the exact optimum`() {
        // min −x₁ − x₂ over x₁ + 2x₂ ≤ 4, 3x₁ + x₂ ≤ 6 with x open above: optimum −14/5.
        val model = working(
            listOf(listOf(0 to 1L, 1 to 3L), listOf(0 to 2L, 1 to 1L)),
            listOf(4L, 6L),
            List(4) { nonnegative },
            listOf(-1L, -1L, 0L, 0L),
        )
        val primal = assertNotNull(RevisedSimplex(model).use { it.solvePrimal() })

        RevisedSimplex(model).use { solver ->
            val result = assertNotNull(solver.solve())
            val certified = certifyLpResult(model, solver, result)

            assertEquals(1, solver.lastNumericalMetrics.artificialStarts)
            assertEquals(0, solver.lastNumericalMetrics.artificialHandoffs)
            assertTrue(abs(result.objective - primal.objective) <= 1e-9)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
            assertEquals(BigFraction.ofLong(-14L) * BigFraction.ofLong(5L).reciprocal(), certified.lowerBound)
        }
    }

    @Test
    fun `an artificial bound active at the boxed optimum should widen to the true optimum`() {
        // min −x₁ over x₁ − 10000x₂ ≤ 0, 2x₂ ≤ 1000: x₁ = 5e6 lies past the initial 1e6 box.
        val model = working(
            listOf(listOf(0 to 1L), listOf(0 to -10_000L, 1 to 2L)),
            listOf(0L, 1000L),
            List(4) { nonnegative },
            listOf(-1L, 0L, 0L, 0L),
        )

        RevisedSimplex(model, scalingOptions = LpScalingOptions(enabled = false)).use { solver ->
            val result = assertNotNull(solver.solve())
            val certified = certifyLpResult(model, solver, result)

            assertEquals(1, solver.lastNumericalMetrics.artificialWidenings)
            assertEquals(0, solver.lastNumericalMetrics.artificialHandoffs)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
            assertEquals(BigFraction.ofLong(-5_000_000L), certified.lowerBound)
        }
    }

    @Test
    fun `suspending boxed dual iteration should preserve widening and handoff paths`() {
        val models = listOf(
            working(
                listOf(listOf(0 to 1L), listOf(0 to -10_000L, 1 to 2L)),
                listOf(0L, 1000L),
                List(4) { nonnegative },
                listOf(-1L, 0L, 0L, 0L),
            ),
            working(
                listOf(listOf(0 to 10_000L), listOf(0 to 1L)),
                listOf(0L),
                listOf(
                    ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(500L))),
                    ExactLpBounds(),
                    ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L))),
                ),
                listOf(20_000L, 1L, 0L),
            ),
        )
        for (model in models) {
            RevisedSimplex(model, scalingOptions = LpScalingOptions(enabled = false)).use { uninterrupted ->
                val expected = assertNotNull(uninterrupted.solve())
                RevisedSimplex(model, scalingOptions = LpScalingOptions(enabled = false)).use { sliced ->
                    sliced.enableProgressTrace()
                    sliced.openSolve().use { cursor ->
                        var step = cursor.advance(1L)
                        var pauses = 0
                        while (step is LpSolveStep.Paused && pauses < 64) {
                            pauses++
                            assertNull(sliced.solvedExactState)
                            assertNull(sliced.infeasibleRay)
                            step = cursor.advance(1L)
                        }
                        val result = assertNotNull(assertIs<LpSolveStep.Completed<FloatLpResult?>>(step).value)

                        assertTrue(pauses > 0)
                        val phases = assertNotNull(sliced.progressTrace).samples.map { it.phase }
                        assertTrue(SimplexPhase.BOXED_DUAL in phases)
                        assertEquals(expected.objective, result.objective)
                        assertEquals(expected.primal.toList(), result.primal.toList())
                        assertEquals(uninterrupted.lastMetrics.pivots, sliced.lastMetrics.pivots)
                        assertEquals(uninterrupted.lastMetrics.workOps, sliced.lastMetrics.workOps)
                        assertEquals(uninterrupted.lastNumericalMetrics.artificialWidenings,
                            sliced.lastNumericalMetrics.artificialWidenings)
                        assertEquals(uninterrupted.lastNumericalMetrics.artificialHandoffs,
                            sliced.lastNumericalMetrics.artificialHandoffs)
                        assertEquals(uninterrupted.lastTermination, sliced.lastTermination)
                    }
                }
            }
        }
    }

    @Test
    fun `an unbounded lp should not be reported optimal on artificial bounds`() {
        // min −x₁ over x₁ − x₂ ≤ 1 with x open above: x₁ = x₂ + 1 grows without bound.
        val model = working(
            listOf(listOf(0 to 1L), listOf(0 to -1L)),
            listOf(1L),
            List(3) { nonnegative },
            listOf(-1L, 0L, 0L),
        )

        RevisedSimplex(model).use { solver ->
            val result = solver.solve()

            assertNull(result)
            assertEquals(1, solver.lastNumericalMetrics.artificialHandoffs)
            assertEquals(LpFloatTermination.UNBOUNDED_CANDIDATE, solver.lastTermination)
        }
    }

    @Test
    fun `a ray of the boxed lp should not be reported as infeasibility of the real lp`() {
        // min 20000x₂ + x₃ over 10000x₂ + x₃ + s = 0, s ∈ [0, 1], x₂ ≥ 500, x₃ free: the start box x₃ ≥ −1e6
        // cannot reach x₃ ≈ −5e6, so the boxed LP is infeasible while the real one is not.
        val model = working(
            listOf(listOf(0 to 10_000L), listOf(0 to 1L)),
            listOf(0L),
            listOf(
                ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(500L))),
                ExactLpBounds(),
                ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L))),
            ),
            listOf(20_000L, 1L, 0L),
        )

        RevisedSimplex(model, scalingOptions = LpScalingOptions(enabled = false)).use { solver ->
            val result = assertNotNull(solver.solve())

            assertNull(solver.infeasibleRay)
            assertEquals(1, solver.lastNumericalMetrics.artificialHandoffs)
            assertEquals(4_999_999.0, result.objective)
        }
    }
}
