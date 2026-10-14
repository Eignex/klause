package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.ElementResultDefinition
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NValueClauseCompletionTest {
    @Test
    fun `retained completions remain applicable after another proposal`() {
        val p = Problem(
            2, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        state.invariants = InvariantNetwork(
            listOf(
                DefinitionalSweep.SweepNode.CmpReif(0, longArrayOf(1), intArrayOf(0), 1, LinearOp.EQ),
                DefinitionalSweep.SweepNode.CmpReif(1, longArrayOf(1), intArrayOf(1), 1, LinearOp.EQ),
            ),
            2, 2,
        )
        state.recompute()
        val before = state.assignment.snapshot()
        val completion = NValueClauseCompletion(state, state.moveSink, intArrayOf(), 0)

        val retained = completion.complete(Move.IntSet(0, 1))
        completion.complete(Move.IntSet(0, 0))
        assertEquals(before, state.assignment.snapshot())
        state.apply(retained)

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(1L, state.assignment.intValue(1))
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `class completion reconciles indicators reading a maintained integer output`() {
        val p = Problem(
            2, 4,
            arrayOf(IntDomain(1, 2), IntDomain(0, 1), IntDomain(0, 1), IntDomain(1, 1)),
            arrayOf<Factor>(
                NValue(0, intArrayOf(1, 1, 3, 3)),
                Element(1, 2, longArrayOf(0, 1), false, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        state.invariants = InvariantNetwork(listOf(ElementResultDefinition(p.factors[1] as Element)), 4, 2)
        state.assignment.setInt(0, 2)
        state.assignment.setInt(3, 1)
        state.assignment.setBool(0, true)
        state.recompute()
        val before = state.assignment.snapshot()
        assertEquals(0L, state.cost)
        val coordinator = NValueClauseCompletion(state, state.moveSink, intArrayOf(1, 3), 0)

        val move = coordinator.complete(Move.Compound(listOf(Move.IntSet(1, 1), Move.IntSet(0, 1))))
        assertEquals(before, state.assignment.snapshot())
        state.apply(move)

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(1L, state.assignment.intValue(2))
        assertEquals(false, state.assignment.boolValue(0))
        assertEquals(true, state.assignment.boolValue(1))
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `class absorption reassigns a recipient through its maintained result domain`() {
        for (protection in listOf("none", "pin", "owner", "domain")) {
            val p = Problem(
                0, 9,
                arrayOf(
                    IntDomain(2, 3), IntDomain(0, 2),
                    if (protection == "domain") IntDomain(0, 0) else IntDomain(0, 1),
                    IntDomain(1, 1), IntDomain(4, 5), IntDomain(3, 3), IntDomain(5, 5),
                    IntDomain(2, 5).excludeValue(3).excludeValue(4), IntDomain(3, 4),
                ),
                arrayOf<Factor>(
                    NValue(0, intArrayOf(1, 2, 3)),
                    Element(1, 7, longArrayOf(4, 5, 6), true, 0),
                    Element(2, 8, longArrayOf(4, 5, 6), true, 0),
                ),
            )
            val pins = if (protection == "pin") Assumptions(ints = mapOf(2 to 0L)) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(p), Random(3), pins)
            state.invariants = InvariantNetwork(
                listOf(
                    ElementResultDefinition(p.factors[1] as Element),
                    ElementResultDefinition(p.factors[2] as Element),
                ),
                9, 0,
            )
            if (protection == "owner") {
                state.moveSink.setOwners(IntArray(9) { if (it == 2) 1 else -1 })
                state.moveSink.proposer = 0
            }
            for ((v, value) in listOf(
                0 to 3L, 1 to 2L, 2 to 0L, 3 to 1L, 4 to 4L,
                5 to 3L, 6 to 5L, 7 to 5L, 8 to 4L,
            )) state.assignment.setInt(v, value)
            state.recompute()
            val before = state.assignment.snapshot()
            assertEquals(0L, state.cost)
            val coordinator = NValueClauseCompletion(state, state.moveSink, intArrayOf(1, 2, 3), 0)

            val move = coordinator.complete(Move.Compound(listOf(Move.IntSet(1, 0), Move.IntSet(0, 2))))
            assertEquals(before, state.assignment.snapshot())
            state.apply(move)

            assertEquals(0L, state.assignment.intValue(1))
            assertEquals(2L, state.assignment.intValue(0))
            if (protection == "none") {
                assertEquals(1L, state.assignment.intValue(2))
                assertEquals(5L, state.assignment.intValue(4))
                assertEquals(0L, state.cost)
                state.recompute()
                assertEquals(0L, state.cost)
            } else {
                assertEquals(0L, state.assignment.intValue(2))
                assertTrue(state.cost > 0L)
            }
        }
    }
}
