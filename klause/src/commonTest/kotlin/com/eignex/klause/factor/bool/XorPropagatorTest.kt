package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.FinitePropagationInterrupted
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        for (stopAfter in 1..12) {
            val literals = IntArray(3) { Lit.make(it, true) }
            val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(Xor(literals, 1)))
            val state = PropagationState(problem, Assumptions.None)
            assertNull(state.runToFixpoint(allFactors = true))
            assertTrue(state.pinBool(0, false))
            assertTrue(state.pinBool(1, false))
            val propagator = XorPropagator(intArrayOf(0, 1, 2), intArrayOf(), literals, 1)
            var polls = 0
            state.finiteWork.cancellation = object : Cancellation {
                override fun isCancelled(): Boolean = ++polls >= stopAfter
                override fun workMeter(): WorkMeter = WorkMeter { }
            }

            assertFailsWith<FinitePropagationInterrupted> { propagator.propagate(state, 0) }

            assertNull(state.boolValues[2])
            state.finiteWork.cancellation = Cancellation.Never
            assertTrue(propagator.propagate(state, 0))
            assertEquals(true, state.boolValues[2])
        }
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

    @Test
    fun `unchanged unassigned parity terms should keep repeated wakes within bounded work`() {
        val literals = IntArray(64) { Lit.make(it, true) }
        val problem = Problem(64, 0, emptyArray(), arrayOf<Factor>(Xor(literals, 1)))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        for (column in 0..60) assertTrue(state.pinBool(column, false))
        val propagator = XorPropagator(IntArray(64) { it }, intArrayOf(), literals, 1)
        assertTrue(propagator.propagate(state, 0))
        val before = state.work

        repeat(8) { assertTrue(propagator.propagate(state, 0)) }

        assertTrue(state.work - before <= 24L)
        for (column in 61..63) assertNull(state.boolValues[column])
    }

    @Test
    fun `restored parity terms should be reconsidered before a sibling deduction`() {
        val literals = IntArray(4) { Lit.make(it, true) }
        val problem = Problem(4, 0, emptyArray(), arrayOf<Factor>(Xor(literals, 1)))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoLogging = true
        val root = state.mark()
        val propagator = XorPropagator(intArrayOf(0, 1, 2, 3), intArrayOf(), literals, 1)
        assertTrue(state.pinBool(0, false))
        assertTrue(state.pinBool(1, false))
        assertTrue(propagator.propagate(state, 0))
        state.undoTo(root)
        assertTrue(state.pinBool(2, false))
        assertTrue(state.pinBool(3, false))
        assertTrue(propagator.propagate(state, 0))
        assertTrue(state.pinBool(0, true))

        assertTrue(propagator.propagate(state, 0))

        assertEquals(false, state.boolValues[1])
    }

    @Test
    fun `cancelled parity terms should not suppress a remaining unit deduction`() {
        val literals = intArrayOf(0, 0, 2, 4, 6)
        val problem = Problem(4, 0, emptyArray(), arrayOf<Factor>(Xor(literals, 1)))
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.seed(Assumptions.None))
        assertIs<PropagationResult.Implied>(session.pinBool(1, false))

        assertIs<PropagationResult.Implied>(session.pinBool(2, false))

        assertEquals(true, session.boolValue(3))
        assertNull(session.boolValue(0))
    }

    @Test
    fun `cached unassigned parity terms should still observe cancellation`() {
        val literals = IntArray(3) { Lit.make(it, true) }
        val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(Xor(literals, 1)))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        val propagator = XorPropagator(intArrayOf(0, 1, 2), intArrayOf(), literals, 1)
        state.finiteWork.cancellation = Cancellation { true }

        assertFailsWith<FinitePropagationInterrupted> { propagator.propagate(state, 0) }

        assertNull(propagator.conflictReason(state, 0))
        for (column in 0..2) assertNull(state.boolValues[column])
        state.finiteWork.cancellation = Cancellation.Never
        assertTrue(propagator.propagate(state, 0))
    }
}
