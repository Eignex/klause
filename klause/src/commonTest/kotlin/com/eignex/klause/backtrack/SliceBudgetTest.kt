package com.eignex.klause.backtrack

import com.eignex.klause.solver.result.SearchStatsSink
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SliceBudgetTest {

    @Test
    fun `vivification probes spend the slice at their own rate`() {
        val search = SearchStatsSink()
        val slice = SliceBudget({ search.searchWork }) { 0L }
        slice.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 10L)

        search.observeInprocessing(probes = 9L * INPROCESS_PROBES_PER_NODE, visits = 0L)
        assertFalse(slice.expired())
        search.observeInprocessing(probes = INPROCESS_PROBES_PER_NODE, visits = 0L)

        assertTrue(slice.expired())
    }

    @Test
    fun `subsumption visits spend the slice at their own rate`() {
        val search = SearchStatsSink()
        val slice = SliceBudget({ search.searchWork }) { 0L }
        slice.begin(sliceMillis = Long.MAX_VALUE, sliceNodes = 10L)

        search.observeInprocessing(probes = 0L, visits = 9L * INPROCESS_VISITS_PER_NODE)
        assertFalse(slice.expired())
        search.observeInprocessing(probes = 0L, visits = INPROCESS_VISITS_PER_NODE)

        assertTrue(slice.expired())
    }

    @Test
    fun `a work-bounded slice also ends at its time`() {
        val search = SearchStatsSink()
        val slice = SliceBudget({ search.searchWork }) { 0L }

        slice.begin(sliceMillis = 0L, sliceNodes = 10L)

        assertTrue(slice.expired())
    }
}
