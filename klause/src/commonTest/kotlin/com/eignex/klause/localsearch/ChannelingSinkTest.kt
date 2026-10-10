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
    fun `binary channels track the final parity of repeated indicator flips`() {
        for (flips in listOf(1, 2, 3)) {
            val problem = Problem(
                1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 1)),
                arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1)),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(1, 0)
            state.assignment.setBool(0, false)
            state.recompute()
            val sink = ChannelingSink(0, 1)
            repeat(flips) { sink.add(Move.BoolFlip(0)) }

            sink.carryBinaryChannels(state)
            state.apply(sink.toMove())

            assertEquals((flips % 2).toLong(), state.assignment.intValue(1))
            assertEquals(0L, state.cost)
        }
    }
}
