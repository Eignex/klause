package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertFailsWith

class BacktrackParamsTest {
    @Test
    fun `non-positive luby units should be rejected during construction`() {
        for (base in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { BacktrackParams(lubyRestartBase = base) }
        }
    }

    @Test
    fun `copies should reject invalid luby units even under adaptive restarts`() {
        val params = BacktrackParams(emaRestart = true)

        for (base in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { params.copy(lubyRestartBase = base) }
        }
    }
}
