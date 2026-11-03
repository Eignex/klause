package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.FinitePropagationInterrupted
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AffineXorPropagatorTest {
    @Test
    fun `assigned affine combinations should determine values without coordinate assignments`() {
        for (phase in listOf(false, true)) {
            val seed = AffineXorSeed(intArrayOf(-1, -2, -3, 0, 1, 2), intArrayOf(0, 0, 0),
                arrayOf(longArrayOf(3L), longArrayOf(6L), longArrayOf(5L)), 3)
            val rows = listOf(Xor(intArrayOf(0, 2, 6), 0), Xor(intArrayOf(2, 4, 8), 0),
                Xor(intArrayOf(0, 4, 10), 0))
            val problem = Problem(6, 0, emptyArray(), arrayOf<Factor>(GaussianXor(rows, seed)))
            val state = PropagationState(problem, Assumptions.None)
            assertNull(state.runToFixpoint(allFactors = true))
            assertTrue(state.pinBool(3, phase))
            assertTrue(state.pinBool(4, true))

            assertTrue(AffineXorPropagator(seed).propagate(state, 0))

            assertEquals(!phase, state.boolValues[5])
            for (variable in 0 until 3) assertNull(state.boolValues[variable])
        }
    }

    @Test
    fun `opening affine sessions should stop at their reported propagation work`() {
        val seed = AffineXorSeed(intArrayOf(-1, -2, 0), intArrayOf(0), arrayOf(longArrayOf(3L)), 2)
        val rows = listOf(Xor(intArrayOf(0, 2, 4), 0))
        val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(GaussianXor(rows, seed)))
        var readWork: () -> Long = { 0L }
        val budget = 40L
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = readWork() >= budget
            override fun workMeter(): WorkMeter = WorkMeter { }
        }

        val session = PropagationSession(PropagationProblem(problem), cancellation = token,
            onPropagationWork = { readWork = it })

        assertTrue(session.fixpointCancelled)
        assertTrue(session.work in budget..budget + 64L)
        assertNull(session.boolValue(0))
        assertNull(session.boolValue(1))
        assertNull(session.boolValue(2))
    }

    @Test
    fun `affine pins should require bounded assigned support`() {
        for (width in listOf(64, 65)) {
            val coordinates = IntArray(width + 1) { if (it < width) -it - 1 else 0 }
            val dependencies = LongArray((width + 63) / 64) { if (it == 0) -1L else 1L }
            val seed = AffineXorSeed(coordinates, intArrayOf(0), arrayOf(dependencies), width)
            val rows = listOf(Xor(IntArray(width + 1) { Lit.make(it, true) }, 0))
            val problem = Problem(width + 1, 0, emptyArray(), arrayOf<Factor>(GaussianXor(rows, seed)))
            val state = PropagationState(problem, Assumptions.None)
            assertNull(state.runToFixpoint(allFactors = true))
            for (variable in 0 until width) assertTrue(state.pinBool(variable, false))

            assertTrue(AffineXorPropagator(seed).propagate(state, 0))

            if (width == 64) assertEquals(false, state.boolValues[width])
            else assertNull(state.boolValues[width])
        }
    }

    @Test
    fun `inconsistent affine assignments should retain their assigned support`() {
        for (phase in 0..1) {
            val seed = AffineXorSeed(intArrayOf(-1, -2, 0), intArrayOf(phase), arrayOf(longArrayOf(3L)), 2)
            val rows = listOf(Xor(intArrayOf(0, 2, 4), phase))
            val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(GaussianXor(rows, seed)))
            val state = PropagationState(problem, Assumptions.None)
            assertNull(state.runToFixpoint(allFactors = true))
            assertTrue(state.pinBool(0, false))
            assertTrue(state.pinBool(1, false))
            assertTrue(state.pinBool(2, phase == 0))
            val propagator = AffineXorPropagator(seed)

            assertFalse(propagator.propagate(state, 0))

            assertContentEquals(intArrayOf(0, 2, Lit.make(2, phase != 0)), propagator.conflictReason(state, 0))
        }
    }

    @Test
    fun `restored affine assignments should determine the sibling parity`() {
        val seed = AffineXorSeed(intArrayOf(-1, -2, 0), intArrayOf(0), arrayOf(longArrayOf(3L)), 2)
        val rows = listOf(Xor(intArrayOf(0, 2, 4), 0))
        val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(GaussianXor(rows, seed)))
        val state = PropagationState(problem, Assumptions.None)
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoLogging = true
        val root = state.mark()
        val propagator = AffineXorPropagator(seed)
        assertTrue(state.pinBool(0, true))
        assertTrue(state.pinBool(2, false))
        assertTrue(propagator.propagate(state, 0))
        assertEquals(true, state.boolValues[1])

        state.undoTo(root)
        assertTrue(state.pinBool(0, false))
        assertTrue(state.pinBool(2, false))
        assertTrue(propagator.propagate(state, 0))

        assertEquals(false, state.boolValues[1])
    }

    @Test
    fun `fresh affine states should retain independent assignment bases`() {
        val seed = AffineXorSeed(intArrayOf(-1, -2, 0), intArrayOf(0), arrayOf(longArrayOf(3L)), 2)
        val rows = listOf(Xor(intArrayOf(0, 2, 4), 0))
        val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(GaussianXor(rows, seed)))
        val first = PropagationState(problem, Assumptions.None)
        val second = PropagationState(problem, Assumptions.None)
        val propagator = AffineXorPropagator(seed)
        for (state in listOf(first, second)) assertNull(state.runToFixpoint(allFactors = true))
        assertTrue(first.pinBool(0, true))
        assertTrue(first.pinBool(2, false))
        assertTrue(propagator.propagate(first, 0))
        assertTrue(second.pinBool(0, false))
        assertTrue(second.pinBool(2, false))

        assertTrue(propagator.propagate(second, 0))

        assertEquals(true, first.boolValues[1])
        assertEquals(false, second.boolValues[1])
    }

    @Test
    fun `interrupted affine elimination should restart from current assignments`() {
        for (stopAfter in listOf(1, 4, 8, 16, 24, 32)) {
            val seed = AffineXorSeed(intArrayOf(-1, -2, 0), intArrayOf(0), arrayOf(longArrayOf(3L)), 2)
            val rows = listOf(Xor(intArrayOf(0, 2, 4), 0))
            val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(GaussianXor(rows, seed)))
            val state = PropagationState(problem, Assumptions.None)
            assertNull(state.runToFixpoint(allFactors = true))
            assertTrue(state.pinBool(0, false))
            assertTrue(state.pinBool(2, true))
            val propagator = AffineXorPropagator(seed)
            var polls = 0
            state.finiteWork.cancellation = object : Cancellation {
                override fun isCancelled(): Boolean = ++polls >= stopAfter
                override fun workMeter(): WorkMeter = WorkMeter { }
            }

            assertFailsWith<FinitePropagationInterrupted> { propagator.propagate(state, 0) }
            propagator.interrupted(state, 0)
            assertTrue(state.boolValues[1] == null || state.boolValues[1] == true)
            state.finiteWork.cancellation = Cancellation.Never

            assertTrue(propagator.propagate(state, 0))
            assertEquals(true, state.boolValues[1])
        }
    }
}
