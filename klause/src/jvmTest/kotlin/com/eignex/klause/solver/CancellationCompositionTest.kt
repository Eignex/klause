package com.eignex.klause.solver

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class CancellationCompositionTest {

    @Test
    fun `Never never cancels`() {
        assertFalse(Cancellation.Never.isCancelled())
        assertFalse(Cancellation.Never())
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
        val token = Cancellation.after(10.milliseconds)
        assertFalse(token(), "should not cancel immediately")
        Thread.sleep(20)
        assertTrue(token(), "should cancel after the duration")
    }
}
