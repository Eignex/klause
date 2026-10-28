package com.eignex.klause.solver.search

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class SearchClauseResolutionTest {
    @Test
    fun `resolution should remove a complementary pivot and deduplicate remaining literals`() {
        assertContentEquals(intArrayOf(2, 4), resolveSearchClauses(intArrayOf(0, 2), intArrayOf(1, 2, 4), 0))
    }

    @Test
    fun `resolution should decline a tautological result or a missing complementary pivot`() {
        assertNull(resolveSearchClauses(intArrayOf(0, 2), intArrayOf(1, 3), 0))
        assertNull(resolveSearchClauses(intArrayOf(0, 2), intArrayOf(4), 0))
    }
}
