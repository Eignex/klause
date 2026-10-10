package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableInvariantTest {

    @Test
    fun `tuple moves coordinate indicators and their channel product outputs`() {
        for (structured in listOf(false, true)) {
            for (bound in listOf(0, 1)) {
                val problem = Problem(
                    1, 6,
                    arrayOf(
                        IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1),
                        IntDomain(3, 3), IntDomain(0, 3),
                    ),
                    arrayOf<Factor>(
                        Table(intArrayOf(0, 1, 2), longArrayOf(0, 0, 0, 1, 1, 1)),
                        ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                        ReifiedLinear(0, intArrayOf(1), intArrayOf(3), LinearOp.EQ, bound),
                        Product(3, 4, 5),
                    ),
                )
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
                state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 6)).network(6, 1)
                state.assignment.setInt(0, 0)
                state.assignment.setInt(1, 0)
                state.assignment.setInt(2, if (structured) 0L else 1L)
                state.assignment.setInt(3, 1L - bound)
                state.assignment.setInt(4, 3)
                state.assignment.setInt(5, 3L * (1L - bound))
                state.assignment.setBool(0, false)
                state.recompute()
                state.weights.factorWeights[0] = 3.0
                val before = state.assignment.snapshot()
                val cost = state.cost
                state.moveSink.proposer = 0

                if (structured) {
                    state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
                } else {
                    state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
                }
                val move = state.moveSink.list.filterIsInstance<Move.Compound>().first {
                    Move.IntSet(0, 1) in it.parts && Move.IntSet(1, 1) in it.parts
                }
                val predicted = state.netDelta(move)
                val weighted = state.weightedNetDelta(move)
                assertEquals(before, state.assignment.snapshot())
                state.apply(move)

                assertEquals(listOf(1L, 1L, 1L), (0..2).map(state.assignment::intValue))
                assertTrue(state.assignment.boolValue(0))
                assertEquals(bound.toLong(), state.assignment.intValue(3))
                assertEquals(3L * bound, state.assignment.intValue(5))
                assertEquals(state.cost - cost, predicted)
                assertEquals(3.0 * (state.cost - cost), weighted)
                assertEquals(0L, state.cost)
                state.recompute()
                assertEquals(0L, state.cost)
            }
        }
    }

    @Test
    fun `structured tuple moves preserve protected coordinates atomically`() {
        for (protection in listOf("pin", "owner")) {
            val problem = Problem(
                0, 2, Array(2) { IntDomain(0, 1) },
                arrayOf<Factor>(Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1))),
            )
            val assumptions = if (protection == "pin") Assumptions.None.withInt(0, 0) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0), assumptions)
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 0)
            state.recompute()
            if (protection == "owner") state.moveSink.setOwners(intArrayOf(7, -1))
            state.moveSink.proposer = 0
            val before = state.assignment.snapshot()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

            assertTrue(state.moveSink.list.isEmpty())
            assertEquals(before, state.assignment.snapshot())
            assertFalse(state.factors[0].isViolated(state, 0))
        }
    }

    @Test
    fun `an implicitly owned table can enter an interval support`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 3) },
            arrayOf<Factor>(Table(intArrayOf(0, 1), longArrayOf(3, 3, 0, 0), longArrayOf(3, 3, 2, 2))),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.seedImplicitFeasible()
        state.recompute()
        assertEquals(3L, state.assignment.intValue(0))
        assertEquals(3L, state.assignment.intValue(1))
        state.moveSink.proposer = 0

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

        val move = state.moveSink.list.first()
        state.apply(move)
        assertTrue(state.assignment.intValue(0) in 0L..2L)
        assertTrue(state.assignment.intValue(1) in 0L..2L)
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `a wildcard coordinate remains searchable in an owned single row table`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 3) },
            arrayOf<Factor>(Table(intArrayOf(0, 1), longArrayOf(3, Long.MIN_VALUE), longArrayOf(3, Long.MAX_VALUE))),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.seedImplicitFeasible()
        state.recompute()
        state.moveSink.proposer = 0

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

        val move = state.moveSink.list.first()
        state.apply(move)
        assertEquals(3L, state.assignment.intValue(0))
        assertTrue(state.assignment.intValue(1) in 1L..3L)
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `repeated coordinates use a joint support value outside domain holes`() {
        for (reachable in listOf(true, false)) {
            var domain = IntDomain(0, 6).excludeValue(2).excludeValue(3)
            if (!reachable) domain = domain.excludeValue(4)
            val state = LocalSearchState(Problem(0, 1, arrayOf(domain), emptyArray()).bake(), Random(0))
            state.assignment.setInt(0, 0)

            val parts = tableBuildTupleMove(state, intArrayOf(0, 0), longArrayOf(0, 2), 2, longArrayOf(4, 4), 0)

            if (reachable) assertEquals(listOf(Move.IntSet(0, 4)), assertNotNull(parts)) else assertNull(parts)
        }
    }

    @Test
    fun `an unreachable pinned support leaves the seed assignment untouched`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 3) },
            arrayOf<Factor>(Table(intArrayOf(0, 1), longArrayOf(0, 3), longArrayOf(1, 3))),
        )
        val state = LocalSearchState(problem.bake(), Random(0), Assumptions(ints = mapOf(1 to 2L)))
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 2)
        val before = state.assignment.snapshot()

        val seeded = state.factors[0].seedFeasible(state, 0)

        assertFalse(seeded)
        assertEquals(before, state.assignment.snapshot())
    }

    @Test
    fun `a table reading a defined integer declines implicit seeding`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 9)),
            arrayOf<Factor>(
                Product(0, 1, 2),
                Table(intArrayOf(0, 2), longArrayOf(1, 1, 0, 0), longArrayOf(1, 1, 3, 9)),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
            .network(problem.numIntVars, problem.numBoolVars)
        val before = state.assignment.snapshot()

        val seeded = state.factors[1].seedFeasible(state, 1)

        assertFalse(seeded)
        assertEquals(before, state.assignment.snapshot())
    }

    @Test
    fun `delta is negative when move brings assignment closer to a tuple`() {
        // Violated: (1,1). Closest tuple is (0,1) or (2,3) at distance 1.
        // Setting x0=0 brings us to (0,1) → distance 0 → delta < 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 1, 2, 3)),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val delta = state.factors[0].deltaIfIntSet(state, 0, intVar = 0, newValue = 0)
        assertTrue(delta < 0, "move to tuple should reduce violation; delta=$delta")
    }

}
