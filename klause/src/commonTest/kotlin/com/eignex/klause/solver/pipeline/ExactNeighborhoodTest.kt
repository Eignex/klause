package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExactNeighborhoodTest {
    @Test
    fun `a paused root operation should resume without spending a candidate allowance`() {
        var preparations = 0
        var checks = 0
        val search = ExactNeighborhood(0, emptyList(), ExactNeighborhoodCompletion { _, _ ->
            checks++
            ExactNeighborhoodResult.Refuted
        }, coordinateValues = { emptyList() }, maximumMoves = 1,
            prepareBooleanCenter = { ++preparations > 1 }, centerPreparationPending = { preparations == 1 })

        assertFalse(search.advance(Cancellation.Never, 10))
        assertEquals(0.0, search.stats.ls.completions.sum)
        assertTrue(search.advance(Cancellation.Never, 10))

        assertEquals(2, preparations)
        assertEquals(1, checks)
    }

    @Test
    fun `completion exhaustion should stop retries after preparation spends its allowance`() {
        var exhausted = false
        var checks = 0
        val search = ExactNeighborhood(0, listOf(BigFraction.ZERO), ExactNeighborhoodCompletion { _, _ ->
            checks++
            ExactNeighborhoodResult.Undecided
        }, coordinateValues = { emptyList() }, prepareBooleanCenter = {
            exhausted = true
            false
        }, completionExhausted = { exhausted })

        assertTrue(search.advance(Cancellation.Never, Long.MAX_VALUE))

        assertEquals(0, checks)
        assertIs<SolveResult.Unknown>(search.solveResult(TerminationReason.BudgetExhausted))
    }

    @Test
    fun `a paused completion should resume the same candidate before drawing another move`() {
        val conditions = ArrayList<ExactNeighborhoodCondition>()
        var proposals = 0L
        val search = ExactNeighborhood(0, listOf(BigFraction.ZERO), ExactNeighborhoodCompletion { condition, _ ->
            conditions += condition
            if (conditions.size == 1) ExactNeighborhoodResult.Paused else ExactNeighborhoodResult.Refuted
        }, coordinateValues = { emptyList() }, maximumMoves = 1,
            proposal = ExactNeighborhoodProposal { _, _, _, _, _, _ ->
                val pins = mapOf(0 to BigFraction.ofLong(++proposals))
                ExactNeighborhoodCondition(emptyMap(), pins, pins)
            })

        assertFalse(search.advance(Cancellation.Never, 10))
        assertEquals(0.0, search.stats.ls.completions.sum)
        assertTrue(search.advance(Cancellation.Never, 10))

        assertEquals(conditions[0], conditions[1])
        assertEquals(1L, proposals)
        assertEquals(1.0, search.stats.ls.completionsRefuted.sum)
        assertEquals(2L, search.work)
    }

    @Test
    fun `root coordinate copying should resume before proposing a joint repair`() {
        val conditions = ArrayList<ExactNeighborhoodCondition>()
        val search = ExactNeighborhood(0, List(2) { BigFraction.ZERO }, ExactNeighborhoodCompletion { condition, _ ->
            conditions += condition
            ExactNeighborhoodResult.Refuted
        }, coordinateValues = { emptyList() }, maximumMoves = 1, prepareBooleanCenter = { true },
            rootCoordinateValue = { BigFraction.ofLong(it + 3L) })

        assertFalse(search.advance(Cancellation.Never, 1))
        assertTrue(conditions.isEmpty())
        assertTrue(search.advance(Cancellation.Never, 10))

        assertEquals(mapOf(0 to BigFraction.ofLong(3), 1 to BigFraction.ofLong(4)), conditions.single().lower)
    }

    @Test
    fun `root Boolean copying should resume without checking a partial center`() {
        val visited = ArrayList<Int>()
        val conditions = ArrayList<ExactNeighborhoodCondition>()
        val search = ExactNeighborhood(
            3,
            emptyList(),
            ExactNeighborhoodCompletion { condition, _ ->
                conditions += condition
                ExactNeighborhoodResult.Refuted
            },
            coordinateValues = { emptyList() },
            maximumMoves = 1,
            prepareBooleanCenter = { true },
            rootBooleanValue = { variable ->
                visited += variable
                when (variable) {
                    0 -> true
                    1 -> false
                    else -> null
                }
            },
        )

        assertFalse(search.advance(Cancellation.Never, 1))
        assertTrue(conditions.isEmpty())
        assertTrue(search.advance(Cancellation.Never, 10))

        assertEquals(listOf(0, 1, 2), visited)
        assertEquals(mapOf(0 to true, 1 to false, 2 to false), conditions.single().bools)
        assertEquals(4L, search.work)
    }

    @Test
    fun `cancellation should withhold a partially copied Boolean center`() {
        var cancelled = false
        var checks = 0
        val search = ExactNeighborhood(
            2,
            emptyList(),
            ExactNeighborhoodCompletion { _, _ ->
                checks++
                ExactNeighborhoodResult.Refuted
            },
            coordinateValues = { emptyList() },
            maximumMoves = 1,
            prepareBooleanCenter = { true },
            rootBooleanValue = {
                cancelled = true
                true
            },
        )

        assertFalse(search.advance(Cancellation { cancelled }, 10))

        assertEquals(0, checks)
        assertEquals(1L, search.work)
        assertEquals(0.0, search.stats.ls.completions.sum)
    }

    @Test
    fun `Boolean center preparation should not exceed the instruction allowance`() {
        var checks = 0
        val search = ExactNeighborhood(
            2,
            emptyList(),
            ExactNeighborhoodCompletion { _, _ ->
                checks++
                ExactNeighborhoodResult.Refuted
            },
            coordinateValues = { emptyList() },
            maximumInstructions = 1,
            prepareBooleanCenter = { true },
            rootBooleanValue = { true },
        )

        assertTrue(search.advance(Cancellation.Never, 10))

        assertEquals(0, checks)
        assertEquals(1L, search.work)
        assertIs<SolveResult.Unknown>(search.solveResult(TerminationReason.BudgetExhausted))
    }

    @Test
    fun `declined root preparation should keep incomplete move accounting`() {
        var checks = 0
        val search = ExactNeighborhood(
            1,
            emptyList(),
            ExactNeighborhoodCompletion { _, _ ->
                checks++
                ExactNeighborhoodResult.Refuted
            },
            coordinateValues = { emptyList() },
            maximumMoves = 1,
            prepareBooleanCenter = { false },
        )

        assertTrue(search.advance(Cancellation.Never, 10))

        assertEquals(0, checks)
        assertEquals(1.0, search.stats.ls.completionsUndecided.sum)
        assertIs<SolveResult.Unknown>(search.solveResult(TerminationReason.BudgetExhausted))
    }

    @Test
    fun `a refuted neighborhood should not refute the source`() {
        val search = ExactNeighborhood(
            0,
            listOf(BigFraction.ZERO),
            ExactNeighborhoodCompletion { _, _ -> ExactNeighborhoodResult.Refuted },
            coordinateValues = { emptyList() },
            maximumMoves = 3,
        )

        search.advance(Cancellation.Never, 3)

        assertIs<SolveResult.Unknown>(search.solveResult(TerminationReason.BudgetExhausted))
        assertEquals(3.0, search.stats.ls.completionsRefuted.sum)
    }

    @Test
    fun `a zero move allowance should never invoke completion`() {
        var checks = 0
        val search = ExactNeighborhood(
            0,
            emptyList(),
            ExactNeighborhoodCompletion { _, _ ->
                checks++
                ExactNeighborhoodResult.Refuted
            },
            coordinateValues = { emptyList() },
            nodeBudget = NodeBudget(0).allowance,
        )

        search.advance(Cancellation.Never, 10)

        assertEquals(0, checks)
        assertEquals(0.0, search.stats.ls.moves.sum)
    }

    @Test
    fun `failed coordinate repairs should widen retained search windows across slices`() {
        val windows = ArrayList<ExactNeighborhoodCondition>()
        val search = ExactNeighborhood(
            0,
            listOf(BigFraction.ZERO),
            ExactNeighborhoodCompletion { query, _ ->
                windows += query
                ExactNeighborhoodResult.Refuted
            },
            coordinateValues = { emptyList() },
            maximumMoves = 8,
        )

        search.advance(Cancellation.Never, 3)
        search.advance(Cancellation.Never, 5)

        assertEquals(BigFraction.ZERO, windows.first().lower[0])
        assertTrue(windows.any { requireNotNull(it.upper[0]) > BigFraction.ONE })
        assertEquals(8.0, search.stats.ls.moves.sum)
    }

    @Test
    fun `heuristic optimization should keep exact improvements without optimum authority`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val cutoffs = ArrayList<BigFraction?>()
        var checks = 0L
        val search = ExactNeighborhood(
            0,
            listOf(BigFraction.ZERO),
            ExactNeighborhoodCompletion { query, _ ->
                cutoffs += query.cutoff
                ExactNeighborhoodResult.Witness(Sample(booleanArrayOf(), longArrayOf(10 - checks++)))
            },
            objective,
            coordinateValues = { listOf(BigFraction.ofLong(it.intValue(0))) },
            maximumMoves = 3,
        )

        search.advance(Cancellation.Never, 3)

        assertEquals(listOf(null, BigFraction.ofLong(10), BigFraction.ofLong(9)), cutoffs)
        assertEquals(
            8.0,
            assertIs<MinimizeResult.BestFound>(
                search.minimizeResult(TerminationReason.BudgetExhausted),
            ).objective,
        )
    }
}
