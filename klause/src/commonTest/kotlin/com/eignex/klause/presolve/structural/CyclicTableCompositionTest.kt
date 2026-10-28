package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolveContext
import com.eignex.klause.presolve.Presolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CyclicTableCompositionTest {
    @Test
    fun `cyclic joins should refute after pairwise closure and singleton supports`() {
        val rows = LongArray(27) { cell ->
            val row = cell / 3
            when (cell % 3) {
                0 -> (row / 3).toLong()
                1 -> (row % 3).toLong()
                else -> ((6 - row / 3 - row % 3) % 3).toLong()
            }
        }
        val factors = arrayOf<Factor>(
            Table(intArrayOf(0, 1, 2), rows),
            Table(intArrayOf(0, 1, 3), rows),
            Table(intArrayOf(2, 3), longArrayOf(0, 1, 0, 2, 1, 0, 1, 2, 2, 0, 2, 1)),
        )
        for (variable in 0..3) for (value in 0L..2L) {
            val domains = Array(4) { if (it == variable) IntDomain(value, value) else IntDomain(0, 2) }
            val state = PropagationState(Problem(0, 4, domains, factors), Assumptions.None)
            assertNull(state.runToFixpoint(allFactors = true))
        }
        val pairwise = ExtensionalComposition.reduce(
            factors, Cancellation.Never, ExtensionalComposition.Limits(cyclicAttempts = 0),
        )
        assertTrue(pairwise.isEmpty)

        val problem = Problem(0, 4, Array(4) { IntDomain(0, 2) }, factors)
        val result = Presolver.runSource(
            problem, PresolveConfig.parse("off,+extensional"), PresolveContext(solutionSetSensitive = true),
        )

        assertTrue(result.infeasible)
    }

    @Test
    fun `projection should retain reordered private columns and duplicate source rows`() {
        val target = Table(intArrayOf(3, 2, 4), longArrayOf(0, 0, 7, 0, 0, 7, 1, 1, 8, 0, 1, 9))
        val left = Table(intArrayOf(0, 2, 5), longArrayOf(0, 0, 10, 1, 1, 11))
        val right = Table(intArrayOf(3, 0, 6), longArrayOf(0, 0, 12, 1, 1, 13))

        val result = assertIs<ExtensionalIntersection.Restricted>(
            CyclicTableComposition.intersect(target, left, right, { true }, 64),
        )
        val table = assertIs<Table>(result.factor)

        assertContentEquals(target.xs, table.xs)
        assertContentEquals(longArrayOf(0, 0, 7, 0, 0, 7, 1, 1, 8), table.tuples)
    }

    @Test
    fun `projected restrictions should prune ordinary table domains`() {
        val target = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 0, 1, 1, 2, 2))
        val left = Table(intArrayOf(0, 2), longArrayOf(0, 0, 1, 1, 2, 0))
        val right = Table(intArrayOf(1, 2), longArrayOf(0, 0, 1, 1, 2, 1))
        val delta = ExtensionalComposition.reduce(arrayOf(target, left, right), Cancellation.Never)
        val table = delta.addedFactors.filterIsInstance<Table>().single { it.xs.contentEquals(target.xs) }
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(table))
        val state = PropagationState(problem, Assumptions.None)

        assertNull(state.runToFixpoint(allFactors = true))

        assertEquals(IntDomain(0, 1), state.intDomains[0])
        assertEquals(IntDomain(0, 1), state.intDomains[1])
    }

    @Test
    fun `incomplete cyclic projections should publish no partial restriction`() {
        val target = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 1))
        val left = Table(intArrayOf(0, 2), longArrayOf(0, 0, 1, 1))
        val right = Table(intArrayOf(1, 2), longArrayOf(0, 0, 1, 1))
        for (limits in listOf(
            ExtensionalComposition.Limits(cyclicAttempts = 0),
            ExtensionalComposition.Limits(cyclicWork = 10),
            ExtensionalComposition.Limits(cyclicCells = 1),
            ExtensionalComposition.Limits(retainedCells = 0),
        )) {
            val delta = ExtensionalComposition.reduce(arrayOf(target, left, right), Cancellation.Never, limits)

            assertTrue(delta.isEmpty)
            assertFalse(delta.infeasible)
        }
    }

    @Test
    fun `projection should decline cancellation at every unfinished prefix`() {
        val target = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 1))
        val left = Table(intArrayOf(0, 2), longArrayOf(0, 0, 1, 1))
        val right = Table(intArrayOf(1, 2), longArrayOf(0, 0, 1, 1))
        var work = 0
        CyclicTableComposition.intersect(target, left, right, { work++; true }, 64)
        for (allowance in 0 until work) {
            var remaining = allowance

            assertNull(CyclicTableComposition.intersect(target, left, right, { remaining-- > 0 }, 64))
        }
    }

    @Test
    fun `private word indices should keep their all different constraint`() {
        val target = Table(intArrayOf(0, 1, 3), longArrayOf(0, 0, 7, 0, 1, 8, 1, 1, 9))
        val left = Table(intArrayOf(0, 2, 4), longArrayOf(0, 0, 7, 1, 1, 8))
        val right = Table(intArrayOf(1, 2, 5), longArrayOf(0, 0, 8, 1, 1, 9))
        val distinct = AllDifferent(intArrayOf(3, 4, 5))

        val delta = ExtensionalComposition.reduce(arrayOf(target, left, right, distinct), Cancellation.Never)

        assertFalse(3 in delta.droppedIndices)
        assertContentEquals(longArrayOf(0, 0, 7, 1, 1, 9),
            delta.addedFactors.filterIsInstance<Table>().single { it.xs.contentEquals(target.xs) }.tuples)
    }

    @Test
    fun `projected table propagation should restore sibling supports after undo`() {
        val target = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 0, 1, 1))
        val left = Table(intArrayOf(0, 2), longArrayOf(0, 0, 1, 1))
        val right = Table(intArrayOf(1, 2), longArrayOf(0, 0, 1, 1))
        val result = assertIs<ExtensionalIntersection.Restricted>(
            CyclicTableComposition.intersect(target, left, right, { true }, 64),
        )
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(result.factor))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        state.levelToDecisionVar.add(0)
        check(state.excludeIntValue(0, 0))
        assertNull(state.runToFixpoint(allFactors = false))
        assertEquals(IntDomain(1, 1), state.intDomains[1])
        state.undoTo(root)
        state.currentLevel = 1
        state.levelToDecisionVar.add(0)
        check(state.excludeIntValue(0, 1))

        assertNull(state.runToFixpoint(allFactors = false))

        assertEquals(IntDomain(0, 0), state.intDomains[1])
    }

    @Test
    fun `guard columns should retain inactive source alternatives`() {
        val target = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 0, 1, 1))
        val left = Table(intArrayOf(0, 2, 3), longArrayOf(0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 1))
        val right = Table(intArrayOf(1, 2, 3), longArrayOf(0, 1, 0, 1, 0, 0, 0, 0, 1, 1, 1, 1))

        val result = CyclicTableComposition.intersect(target, left, right, { true }, 64)

        assertNull(result)
    }
}
