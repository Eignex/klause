package com.eignex.klause.lp.bounding

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpNodeQueryFundingTest {
    @Test
    fun `query overspend should defer further queries until nodes repay it`() {
        val funding = LpNodeQueryFunding(10L, 2)

        funding.recordWork(35L)

        assertFalse(funding.available)
        funding.observeNode()
        assertFalse(funding.available)
        funding.observeNode()
        assertTrue(funding.available)
    }

    @Test
    fun `idle nodes should not bank credit beyond the warmup capacity`() {
        val funding = LpNodeQueryFunding(10L, 2)
        repeat(100) { funding.observeNode() }

        funding.recordWork(21L)

        assertFalse(funding.available)
    }

    @Test
    fun `pruning probes should not forgive query debt`() {
        val funding = LpNodeQueryFunding(10L, 2)
        val ladder = LpEffortLadder(LpEffort.BOUND, warmup = 1, window = 1, reprobeBase = 1)
        assertTrue(ladder.shouldRun())
        ladder.record(false)
        assertTrue(ladder.shouldRun())

        funding.recordWork(35L)
        ladder.record(true)

        assertTrue(ladder.shouldRun())
        assertFalse(funding.available)
    }

    @Test
    fun `repeated queries should repay preceding query work`() {
        val funding = LpNodeQueryFunding(10L, 2)
        repeat(4) {
            assertTrue(funding.available)
            funding.recordWork(100L)
            repeat(8) { funding.observeNode() }
            assertFalse(funding.available)
            repeat(2) { funding.observeNode() }
        }
    }

    @Test
    fun `zero per node cap should leave queries unrestricted`() {
        val funding = LpNodeQueryFunding(0L, 2)

        repeat(3) { funding.recordWork(Long.MAX_VALUE) }

        assertTrue(funding.available)
    }

    @Test
    fun `large funding amounts should not wrap credit or debt`() {
        val funding = LpNodeQueryFunding(Long.MAX_VALUE, 2)
        funding.observeNode()

        funding.recordWork(Long.MAX_VALUE)

        assertFalse(funding.available)
        funding.observeNode()
        assertTrue(funding.available)
    }
}
