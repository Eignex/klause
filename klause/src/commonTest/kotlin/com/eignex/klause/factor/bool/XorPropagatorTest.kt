package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.FinitePropagationInterrupted
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.propagate
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertIs

class XorPropagatorTest {

    @Test
    fun `single unassigned var should be forced true to reach odd target`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        assertIs<PropagationResult.Implied>(session.pinBool(1, true))
        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `all vars assigned with wrong parity should yield conflict`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }
    @Test
    fun `complementary unassigned literals should retain their parity constant`() {
        for (target in 0..1) {
            val problem = Problem(2, 0, emptyArray(), arrayOf<Factor>(
                Xor(intArrayOf(Lit.make(0, true), Lit.make(0, false), Lit.make(1, true)), target),
            ))

            val session = PropagationSession(problem)
            assertIs<PropagationResult.Implied>(session.seed(Assumptions.None))

            assertEquals(target == 0, session.boolValue(1))
            assertNull(session.boolValue(0))
        }
    }

    @Test
    fun `signed and repeated literals should preserve their complete assignments`() {
        val patterns = listOf(intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(0, 1, 2),
            intArrayOf(0, 0, 3, 4), intArrayOf(1, 2, 5), intArrayOf(1, 1, 2, 3, 4))
        for (literals in patterns) for (target in 0..1) for (mask in 0..7) {
            val values = BooleanArray(3) { mask and (1 shl it) != 0 }
            val parity = literals.count { Lit.evaluate(it, values[Lit.variable(it)]) } and 1
            val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(Xor(literals, target)))

            val result = problem.propagate(Assumptions(bools = (0..2).associateWith { values[it] }))

            if (parity == target) assertIs<PropagationResult.Implied>(result)
            else assertIs<PropagationResult.Unsat>(result)
        }
    }

    @Test
    fun `interrupted parity reasons should withhold their pin and retry`() {
        val literals = IntArray(3) { Lit.make(it, true) }
        val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(Xor(literals, 1)))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        assertTrue(state.pinBool(0, false))
        assertTrue(state.pinBool(1, false))
        val propagator = XorPropagator(intArrayOf(0, 1, 2), intArrayOf(), literals, 1)
        var polls = 0
        state.finiteWork.cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = ++polls >= 5
            override fun workMeter(): WorkMeter = WorkMeter { }
        }

        assertFailsWith<FinitePropagationInterrupted> { propagator.propagate(state, 0) }

        assertNull(state.boolValues[2])
        state.finiteWork.cancellation = Cancellation.Never
        assertTrue(propagator.propagate(state, 0))
        assertEquals(true, state.boolValues[2])
    }

    @Test
    fun `shared parity projections should retain conflict reasons in their own states`() {
        val literals = IntArray(3) { Lit.make(it, true) }
        val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(Xor(literals, 1)))
        val first = PropagationState(problem, Assumptions.None)
        val second = PropagationState(problem, Assumptions.None)
        for (state in listOf(first, second)) assertNull(state.runToFixpoint(allFactors = true))
        for (column in 0..2) {
            assertTrue(first.pinBool(column, false))
            assertTrue(second.pinBool(column, column == 2))
        }
        val propagator = XorPropagator(intArrayOf(0, 1, 2), intArrayOf(), literals, 1)

        assertFalse(propagator.propagate(first, 0))
        assertTrue(propagator.propagate(second, 0))

        assertContentEquals(literals, propagator.conflictReason(first, 0))
        assertNull(propagator.conflictReason(second, 0))
    }
}
