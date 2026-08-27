package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The work counter accumulates operation *totals*, not sample counts.
 *
 * Every other LP counter records units by repeating a unit update, so a single update carrying a large
 * value is the one place the accumulator's semantics matter — and getting it wrong reads as an LP that
 * costs almost nothing.
 */
class LpWorkStatTest {

    @Test
    fun `work observations accumulate their totals`() {
        val sink = LpStatsSink()

        sink.observeWork(1_000L)
        sink.observeWork(2_500L)

        assertEquals(3_500.0, sink.snapshot().workOps.sum, "two solves' work must add up")
    }

    @Test
    fun `a solve that charged nothing does not register`() {
        val sink = LpStatsSink()

        sink.observeWork(0L)

        assertEquals(0.0, sink.snapshot().workOps.sum)
    }
}
