package com.eignex.klause.factor.bool

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.unaryMinus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PseudoBooleanPropagatorTest {
    @Test
    fun `signed word activity overflow should retain Boolean deductions`() {
        val factor = PseudoBoolean(longArrayOf(Long.MAX_VALUE, 1), IntArray(2) { Lit.make(it, true) },
            PbOp.LE, Long.MAX_VALUE)
        val problem = Problem(2, 0, emptyArray(), arrayOf(factor))
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0
        assertTrue(state.pinBool(0, true))

        assertTrue(state.factorAt(0).propagate(state, 0))

        assertEquals(false, state.boolValues[1])
    }

    @Test
    fun `minimum signed coefficient should retain its negated literal semantics`() {
        val factor = PseudoBoolean(longArrayOf(Long.MIN_VALUE), intArrayOf(Lit.make(0, false)),
            PbOp.EQ, Long.MIN_VALUE)
        val problem = Problem(1, 0, emptyArray(), arrayOf(factor))
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        assertTrue(state.factorAt(0).propagate(state, 0))

        assertEquals(false, state.boolValues[0])
    }

    @Test
    fun `wide cancelling aliases should refute an impossible equality without branching`() {
        val wide = parseBigInt("18446744073709551616")
        val factor = PseudoBoolean(arrayOf(wide, -wide), IntArray(2) { Lit.make(0, true) }, PbOp.EQ, bigIntOf(1))
        val problem = Problem(1, 0, emptyArray(), arrayOf(factor))
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        assertFalse(state.factorAt(0).propagate(state, 0))
    }

    @Test
    fun `wide reification should enforce each comparison in both indicator directions`() {
        val wide = parseBigInt("18446744073709551616")
        for (op in PbOp.entries) for (indicator in listOf(false, true)) {
            val bound = if (op == PbOp.LE) BIG_ZERO else wide
            val factor = ReifiedPseudoBoolean(0, arrayOf(wide), intArrayOf(Lit.make(1, true)), op, bound)
            val problem = Problem(2, 0, emptyArray(), arrayOf(factor))
            val state = PropagationState(problem, Assumptions.None)
            state.currentFactor = 0
            assertTrue(state.pinBool(0, indicator))

            assertTrue(state.factorAt(0).propagate(state, 0))

            assertEquals(if (op == PbOp.LE) !indicator else indicator, state.boolValues[1], "$op $indicator")
        }
    }

    @Test
    fun `wide reified deductions should retain entailed source Boolean reasons`() {
        val wide = parseBigInt("18446744073709551616")
        val rng = Random(0x3042)
        repeat(24) { trial ->
            val factor = ReifiedPseudoBoolean(0, arrayOf(wide, -wide, wide),
                IntArray(3) { Lit.make(it + 1, rng.nextBoolean()) }, PbOp.entries[trial % 3], BIG_ZERO)
            val problem = Problem(4, 0, emptyArray(), arrayOf(factor))

            PropagationReasonOracle.assertReasonsImply(problem, "wide-pb-$trial") { state ->
                (0..3).all { rng.nextBoolean() || state.pinBool(it, rng.nextBoolean()) }
            }
        }
    }
}
