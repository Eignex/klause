package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Move
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
        sink.addIntSet(2, Int.MAX_VALUE)
        sink.addIntSet(3, Int.MIN_VALUE)
        sink.addIntSet(4, -1)
        assertEquals(
            listOf(
                Move.IntSet(0, 0),
                Move.IntSet(1, -5),
                Move.IntSet(2, Int.MAX_VALUE),
                Move.IntSet(3, Int.MIN_VALUE),
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
        sink.addBoolFlip(1) // frozen — dropped
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
    fun `growth past initial capacity preserves entries`() {
        val sink = MoveSink()
        for (v in 0 until 100) sink.addIntSet(v, v * 7)
        val out = sink.list
        assertEquals(100, out.size)
        for (v in 0 until 100) assertEquals(Move.IntSet(v, v * 7), out[v])
    }
}
