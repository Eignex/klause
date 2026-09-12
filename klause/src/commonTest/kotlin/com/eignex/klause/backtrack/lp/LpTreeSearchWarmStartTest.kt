package com.eignex.klause.backtrack.lp

import kotlin.test.Test
import kotlin.test.assertEquals

class LpTreeSearchWarmStartTest {
    @Test
    fun `the root crash budget is bounded within node work`() {
        assertEquals(100_000L, rootCrashWorkLimit(0L))
        assertEquals(10L, rootCrashWorkLimit(80L))
        assertEquals(1L, rootCrashWorkLimit(1L))
        assertEquals(100_000L, rootCrashWorkLimit(Long.MAX_VALUE))
    }
}
