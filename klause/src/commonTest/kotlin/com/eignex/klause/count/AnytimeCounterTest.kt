package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnytimeCounterTest {

    @Test
    fun `Boolean counting retains an unchecked sibling when the budget ends`() {
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        for (budget in 1L..3L) {
            val result = solver.exactCount(ExactCountConfig(maxChecks = budget)).last()

            assertEquals(budget - 1L, result.lower, "budget=$budget")
            assertEquals(2L, result.upper, "budget=$budget")
            assertEquals(budget == 3L, result.exact, "budget=$budget")
        }
    }

    @Test
    fun `integer counting retains unchecked values across holes`() {
        val domain = IntDomain(-3, 3).excludeValues(longArrayOf(-2, -1, 1, 2))!!
        val solver = BacktrackSolver(Problem(0, 1, arrayOf(domain), emptyArray()).bake())

        for (budget in 1L..4L) {
            val result = solver.exactCount(ExactCountConfig(maxChecks = budget)).last()

            assertEquals(budget - 1L, result.lower, "budget=$budget")
            assertEquals(3L, result.upper, "budget=$budget")
            assertEquals(budget == 4L, result.exact, "budget=$budget")
        }
    }

    @Test
    fun `sparse integer values across a wide span have their exact projected mass`() {
        val clamp = 1L shl 53
        val domain = intDomainFromSurvivors(longArrayOf(-clamp, 0L, clamp))
        val solver = BacktrackSolver(Problem(0, 1, arrayOf(domain), emptyArray()).bake())

        for (budget in 1L..4L) {
            val result = solver.exactCount(ExactCountConfig(maxChecks = budget)).last()

            assertEquals(budget - 1L, result.lower, "budget=$budget")
            assertEquals(3L, result.upper, "budget=$budget")
            assertEquals(budget == 4L, result.exact, "budget=$budget")
        }
    }

    @Test
    fun `mixed projection retains the deeper frontier at every budget boundary`() {
        val solver = BacktrackSolver(Problem(2, 2, Array(2) { IntDomain(0, 2) }, emptyArray()).bake())
        val config = ExactCountConfig(samplingSet = intArrayOf(1), intSamplingSet = intArrayOf(0), reportEvery = 1L)

        for (budget in 1L..9L) {
            val reports = solver.exactCount(config.copy(maxChecks = budget)).toList()

            for (report in reports) {
                assertTrue(report.lower <= 6L && report.upper >= 6L, "budget=$budget: $report")
                assertEquals(1.0, report.confidence)
                assertTrue(report.estimate in report.lower..report.upper)
            }
            for ((previous, next) in reports.zipWithNext()) {
                assertTrue(next.lower >= previous.lower)
                assertTrue(next.upper <= previous.upper)
            }
            assertEquals(maxOf(0L, budget - 3L), reports.last().lower, "budget=$budget")
            assertEquals(budget == 9L, reports.last().exact, "budget=$budget")
        }
    }

    @Test
    fun `infeasible children tighten the upper bound without discarding feasible siblings`() {
        val problem = Problem(
            2, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val solver = BacktrackSolver(problem.bake())
        val lowerBounds = listOf(0L, 0L, 0L, 1L, 2L, 2L, 3L)
        val upperBounds = listOf(4L, 4L, 4L, 4L, 4L, 3L, 3L)

        for (budget in 1L..7L) {
            val reports = solver.exactCount(ExactCountConfig(maxChecks = budget, reportEvery = 1L)).toList()
            val result = reports.last()

            assertEquals(lowerBounds[budget.toInt() - 1], result.lower, "budget=$budget")
            assertEquals(upperBounds[budget.toInt() - 1], result.upper, "budget=$budget")
            assertEquals(budget == 7L, result.exact, "budget=$budget")
            for ((previous, next) in reports.zipWithNext()) {
                assertTrue(next.lower >= previous.lower)
                assertTrue(next.upper <= previous.upper)
            }
        }
    }

    @Test
    fun `undecided feasibility retains the whole projected space`() {
        val solver = BacktrackSolver(Problem(3, 1, arrayOf(IntDomain(0, 2)), emptyArray()).bake())
        val projections = listOf(
            ExactCountConfig(samplingSet = intArrayOf(0)),
            ExactCountConfig(intSamplingSet = intArrayOf(0)),
            ExactCountConfig(samplingSet = intArrayOf(0), intSamplingSet = intArrayOf(0)),
            ExactCountConfig(samplingSet = intArrayOf()),
        )

        for ((config, upper) in projections.zip(listOf(2L, 3L, 6L, 1L))) {
            val result = solver.exactCount(config.copy(maxDecisionsPerCheck = 0L)).last()

            assertEquals(0L, result.lower)
            assertEquals(upper, result.upper)
            assertFalse(result.exact)
        }
    }

    @Test
    fun `empty and fixed projections count one after feasibility is proven`() {
        val solver = BacktrackSolver(Problem(1, 1, arrayOf(IntDomain(4, 4)), emptyArray()).bake())
        val configs = listOf(
            ExactCountConfig(samplingSet = intArrayOf(), maxChecks = 1L),
            ExactCountConfig(intSamplingSet = intArrayOf(0), maxChecks = 2L),
        )

        for (config in configs) {
            val result = solver.exactCount(config).last()

            assertEquals(Count(1L, 1L, 1L, exact = true, confidence = 1.0), result)
        }
    }

    @Test
    fun `a model without variables has one empty projection`() {
        val solver = BacktrackSolver(Problem(0, 0, emptyArray(), emptyArray()).bake())

        val result = solver.exactCount(ExactCountConfig(maxChecks = 1L)).last()

        assertEquals(Count(1L, 1L, 1L, exact = true, confidence = 1.0), result)
    }

    @Test
    fun `infeasible roots count zero for empty and nonempty projections`() {
        val problem = Problem(
            1, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(0, false)))),
        )
        val solver = BacktrackSolver(problem.bake())

        for (projection in listOf(null, intArrayOf())) {
            val result = solver.exactCount(ExactCountConfig(samplingSet = projection, maxChecks = 1L)).last()

            assertEquals(Count(0L, 0L, 0L, exact = true, confidence = 1.0), result)
        }
    }

    @Test
    fun `integer subtree products beyond Double precision are exact`() {
        val solver = BacktrackSolver(Problem(0, 34, Array(34) { IntDomain(0, 2) }, emptyArray()).bake())

        val result = solver.exactCount(ExactCountConfig(maxChecks = 1L, maxDecisionsPerCheck = 0L)).last()

        assertEquals(0L, result.lower)
        assertEquals(16_677_181_699_666_569L, result.upper)
        assertFalse(result.exact)
    }

    @Test
    fun `an overflowing frontier retains an unbounded upper after a child is checked`() {
        val problem = Problem(
            1024, 0, emptyArray(),
            Array(1024) { Clause(intArrayOf(Lit.make(it, true))) },
        )
        val solver = BacktrackSolver(problem.bake())

        val reports = solver.exactCount(ExactCountConfig(maxChecks = 2L, reportEvery = 1L)).toList()

        for (report in reports) {
            assertEquals(0L, report.lower)
            assertEquals(Long.MAX_VALUE, report.upper)
            assertFalse(report.exact)
        }
    }

    @Test
    fun `large pending sibling mass does not erase a small deeper frontier`() {
        val problem = Problem(
            55, 0, emptyArray(),
            Array(54) { Clause(intArrayOf(Lit.make(it + 1, true))) },
        )
        val solver = BacktrackSolver(problem.bake())

        val reports = solver.exactCount(
            ExactCountConfig(maxChecks = 219L, maxDecisionsPerCheck = 128L, reportEvery = 1L),
        ).toList()

        for (report in reports) {
            assertTrue(report.lower <= 2L && report.upper >= 2L, "$report")
            assertEquals(report.lower == 2L && report.upper == 2L, report.exact)
        }
        for ((previous, next) in reports.zipWithNext()) {
            assertTrue(next.lower >= previous.lower)
            assertTrue(next.upper <= previous.upper)
        }
        assertEquals(Count(2L, 2L, 2L, exact = true, confidence = 1.0), reports.last())
    }
}
