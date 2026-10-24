package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolveContext
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionalCompositionTest {
    @Test
    fun `shared tuples should refute relations whose individual values all have support`() {
        val factors = arrayOf<Factor>(
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1)),
            Table(intArrayOf(1, 0), longArrayOf(0, 1, 1, 0)),
        )
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, factors)
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))

        val delta = Presolve.composeExtensional(problem)

        assertTrue(delta.infeasible)
    }

    @Test
    fun `table filtering should project reordered shared columns and retain private columns`() {
        val factors = arrayOf<Factor>(
            Table(intArrayOf(0, 1, 2), longArrayOf(0, 0, 7, 0, 1, 8, 1, 1, 9)),
            Table(intArrayOf(3, 1, 0), longArrayOf(4, 0, 0, 5, 1, 1)),
        )

        val delta = ExtensionalComposition.reduce(factors, Cancellation.Never)

        assertContentEquals(intArrayOf(0), delta.droppedIndices)
        assertContentEquals(longArrayOf(0, 0, 7, 1, 1, 9), assertIs<Table>(delta.addedFactors.single()).tuples)
    }

    @Test
    fun `interval tables and repeated roles should retain their original factors`() {
        for (table in listOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 0), longArrayOf(1, 1)),
            Table(intArrayOf(0, 0), longArrayOf(0, 1)),
        )) {
            val factors = arrayOf<Factor>(table, Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1)))

            val delta = ExtensionalComposition.reduce(factors, Cancellation.Never)

            assertTrue(delta.isEmpty)
        }
    }

    @Test
    fun `unfinished table intersections should publish no partial restriction`() {
        val factors = arrayOf<Factor>(
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 1)),
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1)),
        )
        for (limits in listOf(
            ExtensionalComposition.Limits(work = 8),
            ExtensionalComposition.Limits(pairWork = 4),
            ExtensionalComposition.Limits(retainedCells = 0),
        )) {
            val delta = ExtensionalComposition.reduce(factors, Cancellation.Never, limits)

            assertTrue(delta.isEmpty)
            assertFalse(delta.infeasible)
        }
    }

    @Test
    fun `cancelled composition should retain completed earlier restrictions`() {
        val left = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 1))
        val right = Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1))
        val factors = arrayOf<Factor>(left, right)
        val budget = PresolveBudget(24)

        val delta = ExtensionalComposition.reduce(factors, budget.orSpent(Cancellation.Never))

        assertContentEquals(intArrayOf(0), delta.droppedIndices)
        assertContentEquals(right.tuples, assertIs<Table>(delta.addedFactors.single()).tuples)
    }

    @Test
    fun `extensional pass should preserve solution sensitive admission`() {
        val config = PresolveConfig.parse("+extensional")

        assertTrue(config.resolved(PresolvePass.COMPOSE_EXTENSIONAL, PresolveContext(solutionSetSensitive = true)))
    }
}
