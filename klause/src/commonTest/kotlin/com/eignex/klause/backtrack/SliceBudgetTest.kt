package com.eignex.klause.backtrack

import com.eignex.klause.solver.result.SearchStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SliceBudgetTest {

    @Test
    fun `expiry polls should account for native propagation completed inside a decision`() {
        var work = 0L
        val slice = SliceBudget({ 0L }, { 0L }, { work })
        slice.begin(Long.MAX_VALUE, 1L)

        work = PROPAGATION_WORK_PER_NODE

        assertTrue(slice.expired())
    }

    @Test
    fun `remaining native LP allowance should account for fractional work and decisions`() {
        var nodes = 0L
        var work = 0L
        val slice = SliceBudget({ nodes }, { work })
        slice.begin(Long.MAX_VALUE, 2L)

        work = LP_WORK_PER_NODE - 1L
        nodes = 1L

        assertEquals(1L, slice.remainingLpWork())
    }

    @Test
    fun `native LP allowance should saturate without wrapping`() {
        val slice = SliceBudget({ 0L }, { 0L })
        slice.begin(Long.MAX_VALUE, Long.MAX_VALUE)

        assertEquals(Long.MAX_VALUE / LP_WORK_PER_NODE * LP_WORK_PER_NODE, slice.remainingLpWork())
    }

    @Test
    fun `unbounded slices should supply no native LP cap`() {
        val slice = SliceBudget({ 0L }, { 0L })
        slice.begin(Long.MAX_VALUE, -1L)

        assertEquals(null, slice.remainingLpWork())
    }

    @Test
    fun `repaid debt should reduce the native LP allowance`() {
        var work = 0L
        val slice = SliceBudget({ 0L }, { work })
        slice.begin(Long.MAX_VALUE, 1L)
        work = 3L * LP_WORK_PER_NODE
        slice.noteOverspend()

        slice.begin(Long.MAX_VALUE, 4L)

        assertEquals(2L * LP_WORK_PER_NODE, slice.remainingLpWork())
    }

    @Test
    fun `search should inherit preparation charges and overspend`() {
        var work = 0L
        val preparation = SliceBudget({ 0L }, { 0L }, { work })
        preparation.begin(Long.MAX_VALUE, 2L)
        work = 5L * PROPAGATION_WORK_PER_NODE
        preparation.noteOverspend()
        val search = SliceBudget({ 0L }, { 0L }, { work }, preparation)

        assertFalse(search.begin(Long.MAX_VALUE, 2L))
        assertTrue(search.begin(Long.MAX_VALUE, 2L))
        assertFalse(search.expired())
        work += PROPAGATION_WORK_PER_NODE
        search.charge()

        assertTrue(search.expired())
        assertEquals(6L, search.spent())
    }

    @Test
    fun `search should inherit fractional preparation charges`() {
        var work = PROPAGATION_WORK_PER_NODE - 1L
        val preparation = SliceBudget({ 0L }, { 0L }, { work })
        preparation.begin(Long.MAX_VALUE, 1L)
        preparation.noteOverspend()
        val search = SliceBudget({ 0L }, { 0L }, { work }, preparation)
        search.begin(Long.MAX_VALUE, 1L)

        work++
        search.charge()

        assertTrue(search.expired())
    }

    @Test
    fun `probe diagnostics should not duplicate propagation work charges`() {
        val search = SearchStatsSink()
        val slice = SliceBudget({ search.searchWork }, { 0L })
        slice.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 10L)

        search.observeInprocessing(probes = 1000L, visits = 0L)

        assertFalse(slice.expired())
    }

    @Test
    fun `scan diagnostics should not duplicate propagation work charges`() {
        val search = SearchStatsSink()
        val slice = SliceBudget({ search.searchWork }, { 0L })
        slice.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 10L)

        search.observeInprocessing(probes = 0L, visits = 1_000_000L)

        assertFalse(slice.expired())
    }

    @Test
    fun `a work-bounded slice should also end at its time`() {
        val search = SearchStatsSink()
        val slice = SliceBudget({ search.searchWork }, { 0L })

        slice.begin(sliceMillis = 0L, sliceNodes = 10L)

        assertTrue(slice.expired())
    }

    @Test
    fun `propagation work should spend the slice without adding decisions`() {
        var work = 0L
        val slice = SliceBudget({ 0L }, { 0L }, { work })
        slice.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 2L)

        work = 2L * PROPAGATION_WORK_PER_NODE
        slice.charge()

        assertTrue(slice.expired())
        assertEquals(2L, slice.spent())
    }

    @Test
    fun `fractional propagation charges should carry across slices`() {
        var work = 0L
        val slice = SliceBudget({ 0L }, { 0L }, { work })
        slice.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 1L)
        work = PROPAGATION_WORK_PER_NODE - 1L
        slice.charge()
        assertFalse(slice.expired())

        slice.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 1L)
        work++
        slice.charge()

        assertTrue(slice.expired())
    }

    @Test
    fun `propagation overspend should be repaid without losing the remaining allowance`() {
        var work = 0L
        val slice = SliceBudget({ 0L }, { 0L }, { work })
        slice.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 2L)
        work = 5L * PROPAGATION_WORK_PER_NODE
        slice.noteOverspend()

        assertFalse(slice.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 2L))
        assertTrue(slice.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 2L))
        work += PROPAGATION_WORK_PER_NODE
        slice.charge()

        assertTrue(slice.expired())
    }

    @Test
    fun `nested search should repay overspend only through its owning budget`() {
        var nodes = 0L
        val owner = SliceBudget({ nodes }, { 0L })
        val nested = SliceBudget({ nodes }, { 0L }, ownsWorkDebt = false)
        owner.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 2L)
        nested.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = owner.remainingNodes())
        nodes = 5L
        nested.noteOverspend()
        owner.noteOverspend()

        assertFalse(owner.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 2L))
        assertTrue(owner.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 2L))
        assertTrue(nested.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = owner.remainingNodes()))
        nodes++

        assertTrue(nested.expired())
        assertTrue(owner.expired())
    }

    @Test
    fun `nested propagation should charge only work within its current slice`() {
        var work = 5L * PROPAGATION_WORK_PER_NODE
        val owner = SliceBudget({ 0L }, { 0L }, { work })
        val nested = SliceBudget({ 0L }, { 0L }, { work }, ownsWorkDebt = false)
        assertFalse(owner.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 2L))
        assertFalse(owner.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 2L))
        assertTrue(owner.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 2L))
        assertTrue(nested.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = owner.remainingNodes()))

        work += PROPAGATION_WORK_PER_NODE
        nested.charge()
        owner.charge()

        assertTrue(nested.expired())
        assertTrue(owner.expired())
    }

    @Test
    fun `settling the same overspend twice should repay it only once`() {
        var nodes = 0L
        val slice = SliceBudget({ nodes }, { 0L })
        slice.begin(Long.MAX_VALUE, 2L)
        nodes = 5L

        slice.noteOverspend()
        slice.noteOverspend()

        assertFalse(slice.begin(Long.MAX_VALUE, 2L))
        assertTrue(slice.begin(Long.MAX_VALUE, 2L))
        assertEquals(1L, slice.remainingNodes())
    }

    @Test
    fun `a large offered slice should not wrap its end`() {
        var nodes = Long.MAX_VALUE - 10L
        val slice = SliceBudget({ nodes }, { 0L })

        assertTrue(slice.begin(Long.MAX_VALUE, Long.MAX_VALUE))
        nodes++

        assertFalse(slice.expired())
        assertEquals(Long.MAX_VALUE - 1L, slice.remainingNodes())
    }
}
