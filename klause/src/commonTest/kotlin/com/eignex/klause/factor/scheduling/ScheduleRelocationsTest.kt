package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.scheduling.internals.CumulativeLsState
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduleRelocationsTest {

    @Test
    fun `compaction should start where every shared resource has room`() {
        val factors = arrayOf<Factor>(
            Cumulative.unary(intArrayOf(0, 1), longArrayOf(2, 3)),
            Cumulative.unary(intArrayOf(0, 2), longArrayOf(2, 1)),
        )
        val domains = arrayOf(IntDomain(0, 10), IntDomain(2, 2), IntDomain(0, 0))
        val state = LocalSearchState(LocalSearchModel.open(Problem(0, 3, domains, factors)), Random(5))
        state.assign(8, 2, 0)
        val sink = state.moveSink

        repeat(8) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

        assertTrue(IntSet(0, 5) in sink.list)
        assertTrue(IntSet(0, 0) !in sink.list)
        for (move in sink.list) assertEquals(0L, state.netDelta(move), "$move")
    }

    @Test
    fun `block compaction should close gaps in start order around shared resources`() {
        val factors = arrayOf<Factor>(
            Cumulative.unary(intArrayOf(0, 1, 2), longArrayOf(2, 2, 1)),
            Cumulative.unary(intArrayOf(2, 3), longArrayOf(1, 1)),
        )
        val domains = arrayOf(IntDomain(0, 0), IntDomain(0, 10), IntDomain(0, 10), IntDomain(4, 4))
        val state = LocalSearchState(LocalSearchModel.open(Problem(0, 4, domains, factors)), Random(17))
        state.assign(0, 4, 8, 4)

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

        val block = assertNotNull(
            state.moveSink.list.filterIsInstance<Move.Compound>().firstOrNull {
                it.parts.toSet() == setOf(IntSet(1, 2), IntSet(2, 5))
            },
        )
        assertEquals(0L, state.netDelta(block))
        assertEquals(listOf(0L, 4L, 8L), listOf(0, 1, 2).map { state.assignment.intValue(it) })
    }

    @Test
    fun `compaction should reassign a channelled machine choice with the start`() {
        val state = machineState()
        state.assignMachines(choice = 0, start = 6)

        repeat(8) { state.factors[MACHINE_ZERO].proposeStructuredMoves(state, MACHINE_ZERO, state.moveSink) }

        assertTrue(IntSet(START, 5) in state.moveSink.list)
        val move = assertNotNull(
            state.moveSink.list.filterIsInstance<Move.Compound>().firstOrNull {
                it.parts.toSet() == setOf(IntSet(START, 1), IntSet(CHOICE, 1))
            },
        )
        assertEquals(0L, state.netDelta(move))
        state.apply(move)
        state.recompute()
        assertEquals(0L, state.cost)
        assertEquals(listOf(1L, 0L, 1L, 1L), listOf(CHOICE, 1, 2, START).map { state.assignment.intValue(it) })
    }

    @Test
    fun `compaction should preserve pinned and owned coordinates`() {
        for (protection in listOf("pinned start", "pinned choice", "owned choice")) {
            val assumptions = when (protection) {
                "pinned start" -> Assumptions.None.withInt(START, 6)
                "pinned choice" -> Assumptions.None.withInt(CHOICE, 0)
                else -> Assumptions.None
            }
            val state = machineState(assumptions)
            if (protection == "owned choice") state.moveSink.setOwners(IntArray(8) { if (it == CHOICE) 9 else -1 })
            state.assignMachines(choice = 0, start = 6)

            repeat(8) { state.factors[MACHINE_ZERO].proposeStructuredMoves(state, MACHINE_ZERO, state.moveSink) }

            val touched = state.moveSink.list.flatMap { if (it is Move.Compound) it.parts else listOf(it) }
            val protectedVar = if (protection == "pinned start") START else CHOICE
            assertTrue(touched.none { it is IntSet && it.varId == protectedVar }, protection)
        }
    }

    @Test
    fun `unequal reorder should require room on every resource and restore the timelines`() {
        for (blocked in listOf(false, true)) {
            val factors = arrayOf<Factor>(
                Cumulative.unary(intArrayOf(0, 1), longArrayOf(3, 1)),
                Cumulative.unary(intArrayOf(1, 2), longArrayOf(1, 1)),
            )
            val domains = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 8))
            val state = LocalSearchState(LocalSearchModel.open(Problem(0, 3, domains, factors)), Random(11))
            state.assign(0, 3, if (blocked) 0 else 8)
            val timelines = (0..1).map { (state.refPayload[it] as CumulativeLsState).usage.copyOf() }

            repeat(16) { state.factors[0].proposeStructuredMoves(state, 0, state.moveSink) }

            val reorder = state.moveSink.list.filterIsInstance<Move.Compound>().firstOrNull {
                it.parts.toSet() == setOf(IntSet(1, 0), IntSet(0, 1))
            }
            assertEquals(!blocked, reorder != null)
            if (reorder != null) assertEquals(0L, state.netDelta(reorder))
            for (id in 0..1) {
                val payload = state.refPayload[id] as CumulativeLsState
                assertContentEquals(timelines[id], payload.usage)
                assertEquals(0L, payload.overage)
            }
            assertEquals(listOf(0L, 3L), listOf(0, 1).map { state.assignment.intValue(it) })
        }
    }

    @Test
    fun `compaction should lower the makespan envelope with the moved task`() {
        val factors = listOf(
            Cumulative.unary(intArrayOf(0, 1), longArrayOf(2, 3)),
            Linear(longArrayOf(1, -1), intArrayOf(0, 2), LinearOp.LE, -2),
            Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, -3),
        )
        val domains = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 20))
        val problem = Problem(0, 3, domains, factors)
        val state = LocalSearchState(LocalSearchModel.open(problem, domains), Random(13))
        state.assign(5, 0, 7)

        repeat(8) { state.factors[0].proposeStructuredMoves(state, 0, state.moveSink) }

        val move = assertNotNull(
            state.moveSink.list.filterIsInstance<Move.Compound>().firstOrNull {
                it.parts.toSet() == setOf(IntSet(0, 3), IntSet(2, 5))
            },
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 1))
        assertEquals(-2.0, state.objectiveDelta(objective, move))
        assertEquals(0L, state.netDelta(move))
    }

    private fun LocalSearchState.assign(vararg values: Long) {
        for ((variable, value) in values.withIndex()) assignment.setInt(variable, value)
        recompute()
    }

    // Task A (start 3) runs on machine 0 or 1 through `ρm = [choice = m]`, beside fixed tasks on each machine
    // and on a unary resource it shares with a fixed task D.
    private fun machineState(assumptions: Assumptions = Assumptions.None): LocalSearchState {
        val factors = arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(CHOICE), LinearOp.EQ, 0),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(CHOICE), LinearOp.EQ, 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 1),
            Cumulative(intArrayOf(START, 4), longArrayOf(2, 4), longArrayOf(1, 1), 1, resourceVars = intArrayOf(1, 6)),
            Cumulative(intArrayOf(START, 5), longArrayOf(2, 1), longArrayOf(1, 1), 1, resourceVars = intArrayOf(2, 6)),
            Cumulative.unary(intArrayOf(START, 7), longArrayOf(2, 2)),
        )
        val domains = arrayOf(
            IntDomain(0, 1),
            IntDomain(0, 1),
            IntDomain(0, 1),
            IntDomain(0, 10),
            IntDomain(0, 0),
            IntDomain(0, 0),
            IntDomain(1, 1),
            IntDomain(3, 3),
        )
        val problem = Problem(2, 8, domains, factors)
        val network = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1, 2))).network(8, 2)
        return LocalSearchState(LocalSearchModel.open(problem), Random(7), assumptions).also { it.invariants = network }
    }

    private fun LocalSearchState.assignMachines(choice: Long, start: Long) {
        assignment.setBool(0, choice == 0L)
        assignment.setBool(1, choice == 1L)
        assign(choice, if (choice == 0L) 1 else 0, if (choice == 1L) 1 else 0, start, 0, 0, 1, 3)
    }

    private companion object {
        const val CHOICE = 0
        const val START = 3
        const val MACHINE_ZERO = 4
    }
}
