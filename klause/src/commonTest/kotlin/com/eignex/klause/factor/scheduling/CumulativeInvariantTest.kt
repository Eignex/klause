package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CumulativeInvariantTest {
    @Test
    fun `conditional durations repair overload through admissible choices`() {
        for ((bound, retained) in listOf(0 to false, 1 to false, 0 to true, 1 to true)) {
            val factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
                Product(1, 2, 3),
                Cumulative(
                    intArrayOf(4, 6), longArrayOf(3, 3), longArrayOf(1, 1), 1,
                    durationVars = intArrayOf(3, 5),
                ),
            )
            val problem = Problem(
                1, 7, arrayOf(
                    IntDomain(0, 2), IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3),
                    IntDomain(0, 0), IntDomain(3, 3), IntDomain(0, 0),
                ), factors,
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            val sweep = if (retained) DefinitionalSweep.infer(problem, intArrayOf(1)) else DefinitionalSweep.infer(factors, 7)
            state.invariants = assertNotNull(sweep).network(7, 1)
            state.assignment.setInt(0, if (bound == 1) 0L else 1L)
            state.assignment.setInt(1, 1)
            state.assignment.setInt(2, 3)
            state.assignment.setInt(3, 3)
            state.assignment.setInt(4, 0)
            state.assignment.setInt(5, 3)
            state.assignment.setInt(6, 0)
            state.assignment.setBool(0, bound == 1)
            state.recompute()
            val before = state.cost
            assertTrue(before > 0L)

            state.factors[3].proposeRepairMoves(state, 3, state.moveSink)
            val move = state.moveSink.list.first()
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(state.assignment.intValue(0) == 0L, state.assignment.boolValue(0))
            assertEquals(0L, state.assignment.intValue(1))
            assertEquals(0L, state.assignment.intValue(3))
            assertEquals(3L, state.assignment.intValue(2))
            assertEquals(-before, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `conditional choice repairs retain destination overload`() {
        val factors = arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 1),
            Product(1, 3, 4),
            Product(2, 3, 5),
            Cumulative(
                intArrayOf(6, 8), longArrayOf(3, 3), longArrayOf(1, 1), 1,
                durationVars = intArrayOf(4, 7),
            ),
            Cumulative(
                intArrayOf(6, 9), longArrayOf(3, 3), longArrayOf(1, 1), 1,
                durationVars = intArrayOf(5, 7),
            ),
        )
        val problem = Problem(
            2, 10, arrayOf(
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(3, 3),
                IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 0), IntDomain(3, 3),
                IntDomain(0, 0), IntDomain(0, 0),
            ), factors,
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1, 2))).network(10, 2)
        for ((variable, value) in longArrayOf(0, 1, 0, 3, 3, 0, 0, 3, 0, 0).withIndex()) {
            state.assignment.setInt(variable, value)
        }
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        state.recompute()
        val before = state.cost

        state.factors[6].proposeRepairMoves(state, 6, state.moveSink)
        val move = state.moveSink.list.single()
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(0L, state.assignment.intValue(4))
        assertEquals(3L, state.assignment.intValue(5))
        assertEquals(0, state.factorDegree[6])
        assertTrue(state.factorDegree[7] > 0)
        assertEquals(0L, predicted)
        assertEquals(before, state.cost)
        state.recompute()
        assertEquals(before, state.cost)
    }

    @Test
    fun `conditional duration repairs preserve pinned and owned coordinates`() {
        for (protection in listOf(
            "choice pin", "choice owner", "channel pin", "channel owner", "indicator pin",
            "duration pin", "duration owner",
        )) {
            val factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                Product(1, 2, 3),
                Cumulative(
                    intArrayOf(4, 6), longArrayOf(3, 3), longArrayOf(1, 1), 1,
                    durationVars = intArrayOf(3, 5),
                ),
            )
            val problem = Problem(
                1, 7, arrayOf(
                    IntDomain(0, 2), IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3),
                    IntDomain(0, 0), IntDomain(3, 3), IntDomain(0, 0),
                ), factors,
            )
            val assumptions = when (protection) {
                "choice pin" -> Assumptions.None.withInt(0, 0L)
                "channel pin" -> Assumptions.None.withInt(1, 1L)
                "indicator pin" -> Assumptions.None.withBool(0, true)
                "duration pin" -> Assumptions.None.withInt(3, 3L)
                else -> Assumptions.None
            }
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 7)).network(7, 1)
            val owned = when (protection) {
                "choice owner" -> 0
                "channel owner" -> 1
                "duration owner" -> 3
                else -> -1
            }
            if (owned >= 0) state.moveSink.setOwners(IntArray(7) { if (it == owned) 7 else -1 })
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 1)
            state.assignment.setInt(2, 3)
            state.assignment.setInt(3, 3)
            state.assignment.setInt(4, 0)
            state.assignment.setInt(5, 3)
            state.assignment.setInt(6, 0)
            state.assignment.setBool(0, true)
            state.recompute()

            state.factors[3].proposeRepairMoves(state, 3, state.moveSink)

            assertTrue(state.moveSink.list.isEmpty(), protection)
        }
    }

    @Test
    fun `conditional durations cannot be removed outside their root domains`() {
        for (zeroMissing in listOf("channel", "duration")) {
            val factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                Product(1, 2, 3),
                Cumulative(
                    intArrayOf(4, 6), longArrayOf(3, 3), longArrayOf(1, 1), 1,
                    durationVars = intArrayOf(3, 5),
                ),
            )
            val problem = Problem(
                1, 7, arrayOf(
                    IntDomain(0, 2), IntDomain(if (zeroMissing == "channel") 1L else 0L, 1L),
                    IntDomain(3, 3), IntDomain(if (zeroMissing == "duration") 1L else 0L, 3L),
                    IntDomain(0, 0), IntDomain(3, 3), IntDomain(0, 0),
                ), factors,
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 7)).network(7, 1)
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 1)
            state.assignment.setInt(2, 3)
            state.assignment.setInt(3, 3)
            state.assignment.setInt(4, 0)
            state.assignment.setInt(5, 3)
            state.assignment.setInt(6, 0)
            state.assignment.setBool(0, true)
            state.recompute()

            state.factors[3].proposeRepairMoves(state, 3, state.moveSink)

            assertTrue(state.moveSink.list.isEmpty(), zeroMissing)
        }
    }

    private fun threeTasksUnary(): Problem {
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = longArrayOf(2, 2, 2),
            resources = longArrayOf(1, 1, 1),
            capacity = 1,
        )
        return Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `incremental apply matches a recompute`() {
        val problem = threeTasksUnary()
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        val before = state.intPayload[0]
        state.apply(IntSet(1, 2))
        state.apply(IntSet(2, 4))
        val afterIncr = state.intPayload[0]
        val fresh = LocalSearchState(problem.bake(), Random(0))
        fresh.assignment.setInt(0, 0)
        fresh.assignment.setInt(1, 2)
        fresh.assignment.setInt(2, 4)
        fresh.recompute()
        assertEquals(0, afterIncr, "spread schedule should be feasible")
        assertEquals(fresh.intPayload[0], afterIncr, "incremental apply must agree with recompute")
        assertTrue(before > 0, "all-at-zero must start violated")
    }

    @Test
    fun `var resources flip overage as the resource var changes`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(2, 2),
            resources = longArrayOf(1, 1), // ubs
            capacity = 1,
            resourceVars = intArrayOf(2, 3),
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 1), IntDomain(0, 1)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 1)
        state.assignment.setInt(3, 1)
        state.recompute()
        assertTrue(state.cost > 0, "both resources at 1 should overload capacity 1")
        state.assignment.setInt(3, 0)
        state.recompute()
        assertEquals(0, state.cost, "zero resource on one task should remove the overage")
    }

    @Test
    fun `var capacity flips overage as the capacity var changes`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(2, 2),
            resources = longArrayOf(1, 1),
            capacity = 2,
            capacityVar = 2,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(1, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 1)
        state.recompute()
        assertTrue(state.cost > 0, "cap=1 with unit overlap should overage")
        state.assignment.setInt(2, 2)
        state.recompute()
        assertEquals(0, state.cost, "raising cap to 2 should clear overage")
    }

    @Test
    fun `var durations rescale task footprint`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(3, 3), // ubs
            resources = longArrayOf(1, 1),
            capacity = 1,
            durationVars = intArrayOf(2, 3),
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(1, 3), IntDomain(1, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 2)
        state.recompute()
        assertEquals(0, state.cost, "duration 2 each at starts 0 and 2 shouldn't overlap")
        state.assignment.setInt(2, 3)
        state.recompute()
        assertTrue(state.cost > 0, "extending d0 to 3 overlaps task 1 at t=2")
    }
}
