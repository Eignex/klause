package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

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
        val completion = NValueClauseCompletion(state, state.moveSink, intArrayOf(0))

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
}
