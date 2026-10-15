package com.eignex.klause.localsearch

import com.eignex.klause.propagation.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals

class MoveSinkTest {

    @Test
    fun `indexed moves should retain filtered primitives and compound order across clear`() {
        val sink = MoveSink(Assumptions(bools = mapOf(1 to true)))
        sink.setOwners(intArrayOf(-1, 7))
        repeat(2) {
            sink.addBoolFlip(0)
            sink.addCompound(listOf(Move.BoolFlip(2), Move.IntSet(0, Long.MIN_VALUE)))
            sink.addBoolFlip(1)
            sink.addIntSet(1, 42)
            sink.addIntSet(0, Long.MAX_VALUE)
            sink.addRealSet(0, -0.0)

            assertEquals(sink.list, List(sink.size) { sink.moveAt(it) })
            assertEquals(4, sink.size)
            sink.clear()
            assertEquals(0, sink.size)
        }
    }

    @Test
    fun `a compound with any frozen part should be dropped entirely`() {
        val sink = MoveSink(Assumptions(ints = mapOf(1 to 5)))
        sink.addCompound(listOf(Move.IntSet(0, 10), Move.IntSet(1, 20))) // 1 is frozen
        sink.addCompound(listOf(Move.IntSet(2, 30), Move.IntSet(3, 40)))
        assertEquals(1, sink.list.size)
    }

    @Test
    fun `an owner should be allowed to move the var it owns`() {
        val sink = MoveSink()
        sink.setOwners(intArrayOf(-1, 7, -1))
        sink.proposer = 7
        sink.addIntSet(1, 20)
        assertEquals(listOf(Move.IntSet(1, 20)), sink.list)
    }

    @Test
    fun `compound parts on a foreign-owned var should be dropped individually`() {
        val sink = MoveSink()
        sink.setOwners(intArrayOf(-1, 7, -1, -1))
        // var 1 owned by 7; proposer is no-one, so the part on 1 drops and the swap collapses to a primitive.
        sink.addCompound(listOf(Move.IntSet(0, 10), Move.IntSet(1, 20)))
        assertEquals(listOf(Move.IntSet(0, 10)), sink.list)
    }
}
