package com.eignex.klause.localsearch

import com.eignex.klause.propagation.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoveSinkTest {

    @Test
    fun `bool flip round-trips through packed lane`() {
        val sink = MoveSink()
        sink.addBoolFlip(0)
        sink.addBoolFlip(1)
        sink.addBoolFlip(42)
        assertEquals(
            listOf(Move.BoolFlip(0), Move.BoolFlip(1), Move.BoolFlip(42)),
            sink.list,
        )
    }

    @Test
    fun `int set round-trips with negative and extreme values`() {
        val sink = MoveSink()
        sink.addIntSet(0, 0)
        sink.addIntSet(1, -5)
        sink.addIntSet(2, Int.MAX_VALUE.toLong())
        sink.addIntSet(3, Int.MIN_VALUE.toLong())
        sink.addIntSet(4, -1)
        assertEquals(
            listOf(
                Move.IntSet(0, 0),
                Move.IntSet(1, -5),
                Move.IntSet(2, Int.MAX_VALUE.toLong()),
                Move.IntSet(3, Int.MIN_VALUE.toLong()),
                Move.IntSet(4, -1),
            ),
            sink.list,
        )
    }

    @Test
    fun `clear empties the sink without losing capacity`() {
        val sink = MoveSink()
        for (v in 0 until 50) sink.addBoolFlip(v)
        assertEquals(50, sink.list.size)
        sink.clear()
        assertTrue(sink.list.isEmpty())
        sink.addBoolFlip(7)
        assertEquals(listOf(Move.BoolFlip(7)), sink.list)
    }

    @Test
    fun `mixed primitives interleave in insertion order`() {
        val sink = MoveSink()
        sink.addBoolFlip(0)
        sink.addIntSet(1, 100)
        sink.addBoolFlip(2)
        sink.addIntSet(3, -100)
        assertEquals(
            listOf(Move.BoolFlip(0), Move.IntSet(1, 100), Move.BoolFlip(2), Move.IntSet(3, -100)),
            sink.list,
        )
    }

    @Test
    fun `compound moves follow primitives in the materialized list`() {
        val sink = MoveSink()
        sink.addBoolFlip(0)
        sink.addCompound(listOf(Move.IntSet(1, 10), Move.IntSet(2, 20)))
        sink.addBoolFlip(3)
        val out = sink.list
        assertEquals(3, out.size)
        assertEquals(Move.BoolFlip(0), out[0])
        assertEquals(Move.BoolFlip(3), out[1])
        assertTrue(out[2] is Move.Compound)
    }

    @Test
    fun `frozen vars are filtered`() {
        val sink = MoveSink(Assumptions(bools = mapOf(1 to true)))
        sink.addBoolFlip(0)
        sink.addBoolFlip(1)
        sink.addBoolFlip(2)
        assertEquals(listOf(Move.BoolFlip(0), Move.BoolFlip(2)), sink.list)
    }

    @Test
    fun `compound with any frozen part is dropped entirely`() {
        val sink = MoveSink(Assumptions(ints = mapOf(1 to 5)))
        sink.addCompound(listOf(Move.IntSet(0, 10), Move.IntSet(1, 20))) // 1 is frozen
        sink.addCompound(listOf(Move.IntSet(2, 30), Move.IntSet(3, 40)))
        assertEquals(1, sink.list.size)
    }

    @Test
    fun `list view is cached until next mutation`() {
        val sink = MoveSink()
        sink.addBoolFlip(0)
        val l1 = sink.list
        val l2 = sink.list
        assertTrue(l1 === l2, "list view must be cached across reads")
        sink.addBoolFlip(1)
        val l3 = sink.list
        assertTrue(l3 !== l1, "cache must invalidate on mutation")
    }

    @Test
    fun `owned int var is filtered when no factor is proposing`() {
        val sink = MoveSink()
        sink.setOwners(intArrayOf(-1, 7, -1)) // var 1 owned by factor 7
        sink.addIntSet(0, 10)
        sink.addIntSet(1, 20)
        sink.addIntSet(2, 30)
        assertEquals(listOf(Move.IntSet(0, 10), Move.IntSet(2, 30)), sink.list)
    }

    @Test
    fun `owner may move the var it owns`() {
        val sink = MoveSink()
        sink.setOwners(intArrayOf(-1, 7, -1))
        sink.proposer = 7
        sink.addIntSet(1, 20)
        assertEquals(listOf(Move.IntSet(1, 20)), sink.list)
    }

    @Test
    fun `compound parts on a foreign-owned var are dropped individually`() {
        val sink = MoveSink()
        sink.setOwners(intArrayOf(-1, 7, -1, -1))
        // var 1 owned by 7; proposer is no-one, so the part on 1 drops and the swap collapses to a primitive.
        sink.addCompound(listOf(Move.IntSet(0, 10), Move.IntSet(1, 20)))
        assertEquals(listOf(Move.IntSet(0, 10)), sink.list)
    }

    @Test
    fun `clearing owners restores the var to the generic neighbourhood`() {
        val sink = MoveSink()
        sink.setOwners(intArrayOf(-1, 7))
        sink.setOwners(null)
        sink.addIntSet(1, 20)
        assertEquals(listOf(Move.IntSet(1, 20)), sink.list)
    }

    @Test
    fun `growth past initial capacity preserves entries`() {
        val sink = MoveSink()
        for (v in 0 until 100) sink.addIntSet(v, (v * 7).toLong())
        val out = sink.list
        assertEquals(100, out.size)
        for (v in 0 until 100) assertEquals(Move.IntSet(v, (v * 7).toLong()), out[v])
    }
}
