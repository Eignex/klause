package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LearnedCapRankingTest {
    @Test
    fun `a deferred admission comparison should preserve completed row policy`() {
        val candidates = IntArrayList().apply { repeat(3) { add(it) } }
        val ranking = LearnedCapRanking(candidates)
        val alive = BooleanArray(3) { true }
        val classified = IntArray(3)
        var budget = ReclamationPreparation(0L, 64L, { false }, {})
        val policy = object : LearnedCapPolicy {
            override fun alive(index: Int): Boolean = alive[index]
            override fun lbd(index: Int): Int { budget.visit(); return 9 - index }
            override fun prepare(index: Int): Boolean { budget.visit(CAP_ROW_POLICY_WORK); return true }
            override fun keep(index: Int): Boolean { classified[index]++; return false }
            override fun retire(index: Int): Boolean { budget.visit(8L); alive[index] = false; return true }
            override fun visited() = Unit
        }
        var slices = 0

        while (ranking.passes == 0L && slices++ < 32) {
            budget = ReclamationPreparation(32L, 64L, { false }, {})
            try { ranking.advance(1, 0, budget, policy) } catch (_: ReclamationPreparation.Deferred) { }
            assertTrue(budget.work <= 32L)
        }

        assertEquals(1L, ranking.passes)
        assertContentEquals(booleanArrayOf(false, false, true), alive)
        assertContentEquals(intArrayOf(1, 1, 1), classified)
        assertContentEquals(intArrayOf(2), candidates.toIntArray())
    }

    @Test
    fun `cancellation after comparison funding should preserve the candidate heap`() {
        val candidates = IntArrayList().apply { repeat(2) { add(it) } }
        val ranking = LearnedCapRanking(candidates)
        val alive = BooleanArray(2) { true }
        var budget = ReclamationPreparation(32L, 64L, { false }, {})
        val policy = object : LearnedCapPolicy {
            override fun alive(index: Int): Boolean = alive[index]
            override fun lbd(index: Int): Int { budget.visit(); return 9 - index }
            override fun prepare(index: Int): Boolean { budget.visit(CAP_ROW_POLICY_WORK); return true }
            override fun keep(index: Int): Boolean = false
            override fun retire(index: Int): Boolean { budget.visit(8L); alive[index] = false; return true }
            override fun visited() = Unit
        }
        assertFailsWith<ReclamationPreparation.Deferred> { ranking.advance(1, 0, budget, policy) }
        budget = ReclamationPreparation(32L, 64L, { false }, {})
        assertFailsWith<ReclamationPreparation.Deferred> { ranking.advance(1, 0, budget, policy) }
        val before = candidates.toIntArray()
        var charged = 0L
        budget = ReclamationPreparation(32L, 64L, { charged >= 2L }, { charged += it })

        assertFailsWith<ReclamationPreparation.Deferred> { ranking.advance(1, 0, budget, policy) }

        assertEquals(2L, charged)
        assertContentEquals(before, candidates.toIntArray())
        assertContentEquals(booleanArrayOf(true, true), alive)
        budget = ReclamationPreparation(64L, 64L, { false }, {})
        ranking.advance(1, 0, budget, policy)
        assertContentEquals(intArrayOf(1), candidates.toIntArray())
    }

    @Test
    fun `a deferred cleanup comparison should preserve the published candidate heap`() {
        val candidates = IntArrayList().apply { repeat(4) { add(it) } }
        val ranking = LearnedCapRanking(candidates)
        val alive = BooleanArray(4) { true }
        var budget = ReclamationPreparation(120L, 256L, { false }, {})
        val policy = object : LearnedCapPolicy {
            override fun alive(index: Int): Boolean = alive[index]
            override fun lbd(index: Int): Int { budget.visit(); return index + 1 }
            override fun prepare(index: Int): Boolean { budget.visit(CAP_ROW_POLICY_WORK); return true }
            override fun keep(index: Int): Boolean = false
            override fun retire(index: Int): Boolean { budget.visit(8L); alive[index] = false; return true }
            override fun visited() = Unit
        }
        assertFailsWith<ReclamationPreparation.Deferred> { ranking.advance(4, 0, budget, policy) }
        alive[0] = false
        ranking.invalidateRetiredHandles()
        budget = ReclamationPreparation(16L, 256L, { false }, {})
        assertFailsWith<ReclamationPreparation.Deferred> { ranking.advance(4, 0, budget, policy) }
        val before = candidates.toIntArray()
        budget = ReclamationPreparation(16L, 256L, { false }, {})

        assertFailsWith<ReclamationPreparation.Deferred> { ranking.advance(4, 0, budget, policy) }

        assertContentEquals(before, candidates.toIntArray())
        budget = ReclamationPreparation(256L, 256L, { false }, {})
        ranking.advance(4, 0, budget, policy)
        assertEquals(1L, ranking.passes)
        assertEquals(listOf(1, 2, 3), candidates.toIntArray().sorted())
    }
}
