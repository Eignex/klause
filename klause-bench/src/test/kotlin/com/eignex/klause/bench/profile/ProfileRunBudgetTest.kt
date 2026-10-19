package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfileRunBudgetTest {
    @ParameterizedTest
    @ValueSource(longs = [0, 1, 149_999])
    fun `review allowance preserves the archive reserve`(elapsed: Long) {
        var now = 0L
        val budget = ProfileRunBudget { now }
        now = elapsed * 1_000_000

        val remaining = budget.remainingMillis(30_000)

        assertEquals(150_000 - elapsed, remaining)
    }

    @ParameterizedTest
    @ValueSource(longs = [150_000, 180_000, 200_000])
    fun `an exhausted review cannot spend the archive reserve`(elapsed: Long) {
        var now = 0L
        val budget = ProfileRunBudget { now }
        now = elapsed * 1_000_000

        assertFailsWith<IllegalStateException> { budget.remainingMillis(30_000) }
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 180_000, Long.MAX_VALUE])
    fun `invalid reserves cannot extend the run budget`(reserve: Long) {
        val budget = ProfileRunBudget { 0 }

        assertFailsWith<IllegalArgumentException> { budget.remainingMillis(reserve) }
    }
}
