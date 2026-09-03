package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

/**
 * The relay holds concurrently published improvements to the order the exchange installed them. Verified
 * in isolation (no executor): the fan-in it corrects is a permutation of arrivals, so replaying every
 * permutation is the whole behaviour, with none of a race's flakiness.
 */
class ImprovementRelayTest {

    private fun improvement(version: Int): AttributedImprovement = AttributedImprovement(
        workerLabel = "bt#$version",
        armId = version,
        elapsed = Duration.ZERO,
        result = MinimizeResult.BestFound(
            Sample(BooleanArray(0), LongArray(0)),
            objective = -version.toDouble(),
            reason = TerminationReason.BudgetExhausted,
        ),
    )

    @Test
    fun `improvements are delivered in install order whatever order they arrive in`() {
        val arrivals = listOf(
            listOf(1L, 2L, 3L),
            listOf(1L, 3L, 2L),
            listOf(2L, 1L, 3L),
            listOf(2L, 3L, 1L),
            listOf(3L, 1L, 2L),
            listOf(3L, 2L, 1L),
        )
        for (arrival in arrivals) {
            val relay = ImprovementRelay()
            val delivered = mutableListOf<Int>()
            for (version in arrival) {
                relay.deliver(version, improvement(version.toInt())) { delivered += it.armId }
            }
            assertEquals(listOf(1, 2, 3), delivered, "arrival $arrival must deliver in install order")
        }
    }

    @Test
    fun `an improvement that overtook its predecessor waits for it`() {
        val relay = ImprovementRelay()
        val delivered = mutableListOf<Int>()

        relay.deliver(2L, improvement(2)) { delivered += it.armId }

        assertEquals(emptyList(), delivered, "version 2 must wait for the version 1 that installed before it")
    }

    @Test
    fun `a version that was already delivered is refused`() {
        val relay = ImprovementRelay()
        relay.deliver(1L, improvement(1)) {}

        assertFailsWith<IllegalArgumentException> { relay.deliver(1L, improvement(1)) {} }
    }
}
