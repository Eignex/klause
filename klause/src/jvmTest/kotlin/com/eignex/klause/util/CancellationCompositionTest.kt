package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class CancellationCompositionTest {

    @Test
    fun `Never never cancels`() {
        assertFalse(Cancellation.Never.isCancelled())
        assertFalse(Cancellation.Never())
    }

    @Test
    fun `lambda SAM-converts to Cancellation`() {
        var triggered = false
        val token: Cancellation = Cancellation { triggered }
        assertFalse(token())
        triggered = true
        assertTrue(token())
    }

    @Test
    fun `or fires when either side cancels`() {
        var a = false
        var b = false
        val combined = Cancellation { a } or Cancellation { b }
        assertFalse(combined())
        a = true
        assertTrue(combined())
        a = false
        b = true
        assertTrue(combined())
    }

    @Test
    fun `and fires only when both sides cancel`() {
        var a = false
        var b = false
        val combined = Cancellation { a } and Cancellation { b }
        assertFalse(combined())
        a = true
        assertFalse(combined())
        b = true
        assertTrue(combined())
    }

    @Test
    fun `after fires once the duration has elapsed`() {
        // Two tokens so neither assertion races the scheduler (sleep only ever oversleeps).
        val farFuture = Cancellation.after(10.minutes)
        assertFalse(farFuture(), "a far-future deadline must not cancel yet")
        val soon = Cancellation.after(10.milliseconds)
        Thread.sleep(100)
        assertTrue(soon(), "should cancel after the duration")
    }
}
