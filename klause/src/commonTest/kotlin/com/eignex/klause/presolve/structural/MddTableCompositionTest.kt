package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.holeReasonFor
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MddTableCompositionTest {
    @Test
    fun `composition should refute disjoint shared tuples before search`() {
        val mdd = Mdd(
            intArrayOf(0, 1), intArrayOf(1, 2, 1), intArrayOf(0, 6, 12),
            longArrayOf(0, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0, 0), 0, intArrayOf(0), 3,
        )
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1))

        val result = MddTableComposition.intersect(mdd, table, { true }, ExtensionalComposition.Limits())

        assertSame(ExtensionalComposition.REFUTED, result)
    }

    @Test
    fun `composition should retain correlations through reconverging states and gaps`() {
        val mdd = Mdd(
            intArrayOf(0, 1, 2), intArrayOf(1, 1, 1, 1), intArrayOf(0, 6, 9, 15),
            longArrayOf(0, 0, 0, 0, 1, 0, 0, 7, 0, 0, 0, 0, 0, 1, 0), 0, intArrayOf(0), 3,
        )
        val table = Table(intArrayOf(2, 3, 0), longArrayOf(0, 8, 0, 1, 9, 1))

        val composed = assertIs<Mdd>(MddTableComposition.intersect(mdd, table, { true }, ExtensionalComposition.Limits()))
        val problem = Problem(0, 3, arrayOf(IntDomain(0, 0), IntDomain(7, 7), IntDomain(0, 1)), arrayOf(composed))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))

        assertEquals(IntDomain(0, 0), state.intDomains[2])
        assertNull(MddTableComposition.intersect(composed, table, { true }, ExtensionalComposition.Limits()))
    }

    @Test
    fun `composition should retain weighted nondeterministic paths`() {
        val mdd = Mdd(
            intArrayOf(0, 1), intArrayOf(1, 1, 1), intArrayOf(0, 12, 20),
            longArrayOf(0, 0, 0, 2, 0, 0, 0, 4, 0, 1, 0, 8, 0, 0, 0, 1, 0, 1, 0, 3),
            0, intArrayOf(0), 4, 2,
        )
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0))

        val composed = assertIs<Mdd>(MddTableComposition.intersect(mdd, table, { true }, ExtensionalComposition.Limits()))

        assertEquals(4, composed.recordStride)
        assertEquals(2, composed.cost)
        for (cost in listOf(3L, 5L, 7L)) {
            val problem = Problem(0, 3, arrayOf(IntDomain(0, 0), IntDomain(0, 0), IntDomain(cost, cost)), arrayOf(composed))
            val state = PropagationState(problem, Assumptions.None)
            assertEquals(cost == 7L, state.runToFixpoint(allFactors = true) != null)
        }
    }

    @Test
    fun `composition declines should leave the original diagram available`() {
        val mdd = independent()
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1))
        for (limits in listOf(
            ExtensionalComposition.Limits(states = 2),
            ExtensionalComposition.Limits(records = 2),
            ExtensionalComposition.Limits(pairWork = 10),
        )) {
            val delta = ExtensionalComposition.reduce(arrayOf(mdd, table), Cancellation.Never, limits)

            assertTrue(delta.isEmpty)
            assertFalse(delta.infeasible)
        }
    }

    @Test
    fun `composed hole explanations should restore sibling supports`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1, 2, 2))
        val composed = assertIs<Mdd>(MddTableComposition.intersect(independent(), table, { true }, ExtensionalComposition.Limits()))
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(composed))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        state.levelToDecisionVar.add(0)
        check(state.excludeIntValue(0, 1))
        assertNull(state.runToFixpoint(allFactors = false))
        val reason = assertNotNull(state.holeReasonFor(1, 1))

        val premises = assertNotNull(state.reasonOf(reason)).map { literal ->
            val atom = Lit.variable(literal) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(listOf(Triple(0, AtomKind.EQ, 1L)), premises)
        assertFalse(1L in state.intDomains[1])
        state.undoTo(root)
        state.currentLevel = 1
        state.levelToDecisionVar.add(0)
        check(state.excludeIntValue(0, 2))
        assertNull(state.runToFixpoint(allFactors = false))
        assertTrue(1L in state.intDomains[1])
        assertFalse(2L in state.intDomains[1])
    }

    private fun independent(): Mdd = Mdd(
        intArrayOf(0, 1), intArrayOf(1, 1, 1), intArrayOf(0, 9, 18),
        longArrayOf(0, 0, 0, 0, 1, 0, 0, 2, 0, 0, 0, 0, 0, 1, 0, 0, 2, 0), 0, intArrayOf(0), 3,
    )
}
