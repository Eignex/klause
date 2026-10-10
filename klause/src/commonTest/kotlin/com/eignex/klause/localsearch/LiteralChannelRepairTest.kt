package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiteralChannelRepairTest {
    @Test
    fun `searched predicate choices preserve protected coordinates`() {
        for (protection in listOf("source pin", "source owner", "output pin", "output owner", "Boolean pin")) {
            val problem = Problem(
                1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 1)),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                    Clause(intArrayOf(Lit.make(0, true))),
                ),
            )
            val assumptions = when (protection) {
                "source pin" -> Assumptions.None.withInt(0, 0)
                "output pin" -> Assumptions.None.withInt(1, 1)
                "Boolean pin" -> Assumptions.None.withBool(0, true)
                else -> Assumptions.None
            }
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(2, 1)
            if (protection == "source owner") state.moveSink.setOwners(intArrayOf(7, -1))
            if (protection == "output owner") state.moveSink.setOwners(intArrayOf(-1, 7))
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 1)
            state.assignment.setBool(0, true)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 1, 0)

            if (protection.startsWith("source")) {
                state.apply(state.moveSink.list.single())
                assertEquals(0L, state.assignment.intValue(0))
                assertTrue(state.factorDegree[0] > 0)
                assertTrue(state.cost > 0L)
            } else {
                assertTrue(state.moveSink.list.isEmpty(), protection)
            }
        }
    }

    @Test
    fun `searched predicates coordinate bounded source choices`() {
        for (bound in listOf(0, 1)) {
            val problem = Problem(
                1, 2, arrayOf(IntDomain(0, 8), IntDomain(0, 1)),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
                    Clause(intArrayOf(Lit.make(0, bound == 1))),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(2, 1)
            state.assignment.setInt(0, if (bound == 1) 0L else 1L)
            state.assignment.setInt(1, 1)
            state.assignment.setBool(0, bound == 1)
            state.recompute()
            val before = state.cost

            state.factors[2].proposeRepairMoves(state, 2, state.moveSink)
            assertEquals(if (bound == 1) 4 else 1, state.moveSink.list.size)
            val move = state.moveSink.list.first()
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(0L, state.assignment.intValue(1))
            assertEquals(state.assignment.intValue(0) == 0L, state.assignment.boolValue(0))
            assertEquals(0, state.factorDegree[0])
            assertEquals(0, state.factorDegree[1])
            assertEquals(0, state.factorDegree[2])
            assertTrue(state.factorDegree[3] > 0)
            assertEquals(state.cost - before, predicted)
            val cost = state.cost
            state.recompute()
            assertEquals(cost, state.cost)
        }
    }

    @Test
    fun `channel fanout preserves independently owned outputs`() {
        val problem = Problem(
            1, 3, arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1, 2))).network(3, 1)
        state.moveSink.setOwners(intArrayOf(-1, -1, 7))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 1)
        state.assignment.setBool(0, true)
        state.recompute()

        state.moveSink.addChannelingIntSet(state, 1, 0)
        val move = state.moveSink.list.first()
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(0L, state.assignment.intValue(1))
        assertEquals(1L, state.assignment.intValue(2))
        assertTrue(state.factorDegree[2] > 0)
        assertEquals(state.cost, predicted)
        val cost = state.cost
        state.recompute()
        assertEquals(cost, state.cost)
    }

    @Test
    fun `independent channel constraints repair through source choices`() {
        for (bound in listOf(0, 1)) {
            val problem = Problem(
                1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 1)),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(2, 1)
            state.assignment.setInt(0, if (bound == 1) 0L else 1L)
            state.assignment.setInt(1, 1)
            state.assignment.setBool(0, bound == 1)
            state.recompute()
            val before = state.cost
            assertTrue(before > 0L)

            state.factors[2].proposeRepairMoves(state, 2, state.moveSink)
            val move = state.moveSink.list.first()
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(0L, state.assignment.intValue(1))
            assertEquals(state.assignment.intValue(0) == 0L, state.assignment.boolValue(0))
            assertEquals(-before, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `channel inverse repairs preserve pinned and owned variables`() {
        for (protection in listOf("output pin", "source pin", "Boolean pin", "output owner", "source owner")) {
            val problem = Problem(
                1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 1)),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                ),
            )
            val assumptions = when (protection) {
                "output pin" -> Assumptions.None.withInt(1, 1)
                "source pin" -> Assumptions.None.withInt(0, 0)
                "Boolean pin" -> Assumptions.None.withBool(0, true)
                else -> Assumptions.None
            }
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(2, 1)
            if (protection == "output owner") state.moveSink.setOwners(intArrayOf(-1, 7))
            if (protection == "source owner") state.moveSink.setOwners(intArrayOf(7, -1))
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 1)
            state.assignment.setBool(0, true)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 1, 0)

            assertTrue(state.moveSink.list.isEmpty(), protection)
        }
    }

    @Test
    fun `channel inverse repairs reject absent target values`() {
        for (missing in listOf("channel", "source")) {
            val problem = Problem(
                1, 2, arrayOf(
                    IntDomain(0, if (missing == "source") 0L else 2L),
                    IntDomain(if (missing == "channel") 1L else 0L, 1L),
                ),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(2, 1)
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 1)
            state.assignment.setBool(0, true)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 1, 0)

            assertTrue(state.moveSink.list.isEmpty(), missing)
        }
    }

    @Test
    fun `channel inverse repairs enter retained extrema`() {
        val problem = Problem(
            1, 4, arrayOf(IntDomain(0, 2), IntDomain(2, 2), IntDomain(0, 2), IntDomain(0, 1)),
            arrayOf<Factor>(
                ArrayMinMax(2, intArrayOf(0, 1), false),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(3), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(3), LinearOp.EQ, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(2, 3))).network(4, 1)
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 0)
        state.assignment.setInt(3, 1)
        state.assignment.setBool(0, true)
        state.recompute()
        val before = state.cost

        state.factors[3].proposeRepairMoves(state, 3, state.moveSink)
        val move = state.moveSink.list.first()
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(0L, state.assignment.intValue(3))
        assertTrue(state.assignment.intValue(2) > 0L)
        assertEquals(-before, predicted)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }
}
