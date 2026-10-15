package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AbsResultRepairTest {
    @Test
    fun `independent inequalities repair a maintained absolute value through either input sign`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-3, 3), IntDomain(0, 3)), arrayOf(
            Abs(0, 1), Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 1),
        ))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.invariants = assertNotNull(DefinitionalSweep.infer(source)).network(2, 0)
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 3)
        state.recompute()
        val sink = MoveSink().also { it.setInvariants(state.invariants) }

        source.factors[1].invariantProjection().proposeRepairMoves(state, 1, sink)

        assertTrue(Move.IntSet(0, 1) in sink.list)
        assertTrue(Move.IntSet(0, -1) in sink.list)
        for (move in sink.list) {
            state.apply(move)
            assertEquals(1L, state.assignment.intValue(1))
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `nested absolute value repair reaches searched inputs without negative intermediate magnitudes`() {
        val source = Problem(0, 3, arrayOf(IntDomain(-3, 3), IntDomain(0, 3), IntDomain(0, 3)),
            arrayOf(Abs(0, 1), Abs(1, 2)))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.invariants = assertNotNull(DefinitionalSweep.infer(source)).network(3, 0)
        for (id in 0..2) state.assignment.setInt(id, 3)
        state.recompute()
        val sink = MoveSink().also { it.setInvariants(state.invariants) }

        sink.addChannelingIntSet(state, 2, 2)

        assertEquals(setOf(Move.IntSet(0, 2), Move.IntSet(0, -2)), sink.list.toSet())
    }

    @Test
    fun `absolute value repairs preserve holes frozen inputs and implicit owners`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-3, 3).excludeValue(2), IntDomain(0, 3)),
            arrayOf(Abs(0, 1)))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.invariants = assertNotNull(DefinitionalSweep.infer(source)).network(2, 0)
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 3)
        state.recompute()
        val sink = MoveSink().also { it.setInvariants(state.invariants) }
        sink.addChannelingIntSet(state, 1, 2)
        assertEquals(listOf(Move.IntSet(0, -2)), sink.list)
        sink.clear()
        sink.setAssumptions(Assumptions(ints = mapOf(0 to 3L)))
        sink.addChannelingIntSet(state, 1, 2)
        assertTrue(sink.list.isEmpty())
        sink.setAssumptions(Assumptions.None)
        sink.setOwners(intArrayOf(7, -1))
        sink.addChannelingIntSet(state, 1, 2)
        assertTrue(sink.list.isEmpty())
    }

    @Test
    fun `negative absolute value targets do not propose repairs`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-3, 3), IntDomain(-3, 3)), arrayOf(Abs(0, 1)))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.invariants = assertNotNull(DefinitionalSweep.infer(source)).network(2, 0)
        val sink = MoveSink().also { it.setInvariants(state.invariants) }

        sink.addChannelingIntSet(state, 1, -1)

        assertTrue(sink.list.isEmpty())
    }
}
