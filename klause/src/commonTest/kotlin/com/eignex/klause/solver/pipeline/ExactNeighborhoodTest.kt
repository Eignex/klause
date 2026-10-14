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
    fun `root Boolean copying resumes without checking a partial center`() {
        val visited = ArrayList<Int>()
        val conditions = ArrayList<ExactNeighborhoodCondition>()
        val search = ExactNeighborhood(3, emptyList(), ExactNeighborhoodCompletion { condition, _ ->
            conditions += condition
            ExactNeighborhoodResult.Refuted
        }, coordinateValues = { emptyList() }, maximumMoves = 1,
            prepareBooleanCenter = { true }, rootBooleanValue = { variable ->
                visited += variable
                when (variable) { 0 -> true; 1 -> false; else -> null }
            })

        assertFalse(search.advance(Cancellation.Never, 1))
        assertTrue(conditions.isEmpty())
        assertTrue(search.advance(Cancellation.Never, 10))

        assertEquals(listOf(0, 1, 2), visited)
        assertEquals(mapOf(0 to true, 1 to false, 2 to false), conditions.single().bools)
        assertEquals(4L, search.work)
    }

    @Test
    fun `cancellation withholds a partially copied Boolean center`() {
        var cancelled = false
        var checks = 0
        val search = ExactNeighborhood(2, emptyList(), ExactNeighborhoodCompletion { _, _ ->
            checks++
            ExactNeighborhoodResult.Refuted
        }, coordinateValues = { emptyList() }, maximumMoves = 1,
            prepareBooleanCenter = { true }, rootBooleanValue = { cancelled = true; true })

        assertFalse(search.advance(Cancellation { cancelled }, 10))

        assertEquals(0, checks)
        assertEquals(1L, search.work)
        assertEquals(0.0, search.stats.ls.completions.sum)
    }

    @Test
    fun `Boolean center preparation cannot exceed the instruction allowance`() {
        var checks = 0
        val search = ExactNeighborhood(2, emptyList(), ExactNeighborhoodCompletion { _, _ ->
            checks++
            ExactNeighborhoodResult.Refuted
        }, coordinateValues = { emptyList() }, maximumInstructions = 1,
            prepareBooleanCenter = { true }, rootBooleanValue = { true })

        assertTrue(search.advance(Cancellation.Never, 10))

        assertEquals(0, checks)
        assertEquals(1L, search.work)
        assertIs<SolveResult.Unknown>(search.solveResult(TerminationReason.BudgetExhausted))
    }

    @Test
    fun `declined root preparation retains incomplete move accounting`() {
        var checks = 0
        val search = ExactNeighborhood(1, emptyList(), ExactNeighborhoodCompletion { _, _ ->
            checks++
            ExactNeighborhoodResult.Refuted
        }, coordinateValues = { emptyList() }, maximumMoves = 1,
            prepareBooleanCenter = { false })

        assertTrue(search.advance(Cancellation.Never, 10))

        assertEquals(0, checks)
        assertEquals(1.0, search.stats.ls.completionsUndecided.sum)
        assertIs<SolveResult.Unknown>(search.solveResult(TerminationReason.BudgetExhausted))
    }

    @Test
    fun `a refuted neighborhood does not refute the source`() {
        val search = ExactNeighborhood(0, listOf(BigFraction.ZERO),
            ExactNeighborhoodCompletion { _, _ -> ExactNeighborhoodResult.Refuted },
            coordinateValues = { emptyList() }, maximumMoves = 3)

        search.advance(Cancellation.Never, 3)

        assertIs<SolveResult.Unknown>(search.solveResult(TerminationReason.BudgetExhausted))
        assertEquals(3.0, search.stats.ls.completionsRefuted.sum)
    }

    @Test
    fun `a zero move allowance never invokes completion`() {
        var checks = 0
        val search = ExactNeighborhood(0, emptyList(), ExactNeighborhoodCompletion { _, _ ->
            checks++
            ExactNeighborhoodResult.Refuted
        }, coordinateValues = { emptyList() }, nodeBudget = NodeBudget(0))

        search.advance(Cancellation.Never, 10)

        assertEquals(0, checks)
        assertEquals(0.0, search.stats.ls.moves.sum)
    }

    @Test
    fun `failed coordinate repairs widen retained search windows across slices`() {
        val windows = ArrayList<ExactNeighborhoodCondition>()
        val search = ExactNeighborhood(0, listOf(BigFraction.ZERO), ExactNeighborhoodCompletion { query, _ ->
            windows += query
            ExactNeighborhoodResult.Refuted
        }, coordinateValues = { emptyList() }, maximumMoves = 8)

        search.advance(Cancellation.Never, 3)
        search.advance(Cancellation.Never, 5)

        assertEquals(BigFraction.ZERO, windows.first().lower[0])
        assertTrue(windows.any { requireNotNull(it.upper[0]) > BigFraction.ONE })
        assertEquals(8.0, search.stats.ls.moves.sum)
    }

    @Test
    fun `heuristic optimization retains exact improvements without optimum authority`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val cutoffs = ArrayList<BigFraction?>()
        var checks = 0L
        val search = ExactNeighborhood(0, listOf(BigFraction.ZERO), ExactNeighborhoodCompletion { query, _ ->
            cutoffs += query.cutoff
            ExactNeighborhoodResult.Witness(Sample(booleanArrayOf(), longArrayOf(10 - checks++)))
        }, objective, coordinateValues = { listOf(BigFraction.ofLong(it.intValue(0))) }, maximumMoves = 3)

        search.advance(Cancellation.Never, 3)

        assertEquals(listOf(null, BigFraction.ofLong(10), BigFraction.ofLong(9)), cutoffs)
        assertEquals(8.0, assertIs<MinimizeResult.BestFound>(
            search.minimizeResult(TerminationReason.BudgetExhausted)).objective)
    }
}
