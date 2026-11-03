package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelingSinkTest {
    @Test
    fun `indicator contributions should retain their first occurrence order`() {
        val sink = ChannelingSink(0, 1)

        for (variable in intArrayOf(2, 2, 4, 2, 4, 5, 5)) sink.addIndicatorFlip(variable)

        assertEquals(
            Move.Compound(listOf(Move.IntSet(0, 1), Move.BoolFlip(2), Move.BoolFlip(4), Move.BoolFlip(5))),
            sink.toMove(),
        )
    }

    @Test
    fun `binary channels should track the final parity of repeated indicator flips`() {
        for (flips in listOf(1, 2, 3)) {
            val problem = Problem(
                1,
                2,
                arrayOf(IntDomain(0, 2), IntDomain(0, 1)),
                arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1)),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(1, 0)
            state.assignment.setBool(0, false)
            state.recompute()
            val sink = ChannelingSink(0, 1)
            repeat(flips) { sink.add(Move.BoolFlip(0)) }

            sink.carryBinaryChannels(state, state.moveSink)
            state.apply(sink.toMove())

            assertEquals((flips % 2).toLong(), state.assignment.intValue(1))
            assertEquals(0L, state.cost)
        }
    }
}
