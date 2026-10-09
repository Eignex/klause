package com.eignex.klause.solver.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearnedClauseActivityTest {
    @Test
    fun `a recent bump outweighs old activity after decay`() {
        val activity = LearnedClauseActivity(SearchLearnedDbPolicy.Activity)
        val old = activity.add(4)
        val recent = activity.add(4)
        activity.bump(old)
        repeat(1_000) { activity.decay() }

        activity.bump(recent)

        assertTrue(recent.activity > old.activity)
    }

    @Test
    fun `rescaling preserves a finite activity ordering`() {
        val activity = LearnedClauseActivity(SearchLearnedDbPolicy.Activity)
        val old = activity.add(4)
        val recent = activity.add(4)
        repeat(50_000) { activity.decay() }

        activity.bump(recent)

        assertTrue(old.activity.isFinite())
        assertTrue(recent.activity.isFinite())
        assertTrue(recent.activity > old.activity)
    }

    @Test
    fun `tiered analysis improves LBD monotonically`() {
        val activity = LearnedClauseActivity(SearchLearnedDbPolicy.Tiered)
        val handle = activity.add(8)
        val explanation = SearchExplanation(intArrayOf(0, 2, 4)).also { it.learnedHandle = handle }

        activity.analyzed(explanation) { 2 }
        activity.analyzed(explanation) { 5 }

        assertEquals(2, handle.lbd)
        assertEquals(1L, activity.lbdImprovements)
    }

    @Test
    fun `a retained explanation keeps its activity handle after compaction`() {
        val activity = LearnedClauseActivity(SearchLearnedDbPolicy.Activity)
        val store = WatchedClauseStore(activity)
        store.add(intArrayOf(0, 2, 4), 3)
        store.add(intArrayOf(6, 8, 10), 3)
        val explanation = store.explanationOf(1)

        store.retain { it == 1 }
        activity.analyzed(explanation) { 3 }

        assertTrue(explanation.learnedHandle === store.handleAt(0))
        assertEquals(2.0, store.handleAt(0)?.activity)
    }
}
