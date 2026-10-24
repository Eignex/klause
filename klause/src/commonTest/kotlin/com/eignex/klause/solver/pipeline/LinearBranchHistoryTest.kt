package com.eignex.klause.solver.pipeline

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals

class LinearBranchHistoryTest {
    private val first = SourceCoordinate(SourceCoordinate.Kind.Integer, 0) to BigFraction.ofLong(2).reciprocal()
    private val second = SourceCoordinate(SourceCoordinate.Kind.Integer, 1) to BigFraction.ofLong(2).reciprocal()

    @Test
    fun `repeated gains in both directions should select a later useful coordinate`() {
        val history = LinearBranchHistory()
        repeat(2) {
            for (upper in listOf(true, false)) {
                history.observe(first, upper, BigFraction.ONE)
                history.observe(second, upper, BigFraction.ofLong(3))
            }
        }

        val selected = history.select(listOf(first, second), Cancellation.Never)

        assertEquals(second, selected)
    }

    @Test
    fun `missing or single directional observations should retain source order`() {
        for (observations in 0..1) {
            val history = LinearBranchHistory()
            repeat(2) { history.observe(second, true, BigFraction.ofLong(3)) }
            repeat(observations) { history.observe(second, false, BigFraction.ofLong(3)) }

            val selected = history.select(listOf(first, second), Cancellation.Never)

            assertEquals(first, selected)
        }
    }

    @Test
    fun `gains should be normalized by the actual directional movement`() {
        val history = LinearBranchHistory()
        val quarter = BigFraction.ofLong(4).reciprocal()
        repeat(2) {
            history.observe(first, true, BigFraction.ONE)
            history.observe(first, false, BigFraction.ONE)
            history.observe(second.first to quarter, true, BigFraction.ONE)
            history.observe(second.first to quarter, false, BigFraction.ofLong(3))
        }

        val selected = history.select(listOf(first, second), Cancellation.Never)

        assertEquals(second, selected)
    }

    @Test
    fun `negative and zero gain histories should retain source order`() {
        for (gain in listOf(BigFraction.MINUS_ONE, BigFraction.ZERO)) {
            val history = LinearBranchHistory()
            repeat(2) {
                history.observe(second, true, gain)
                history.observe(second, false, gain)
            }

            val selected = history.select(listOf(first, second), Cancellation.Never)

            assertEquals(first, selected)
        }
    }

    @Test
    fun `cancellation should retain a valid exhaustive split proposal`() {
        val history = LinearBranchHistory()
        repeat(2) {
            history.observe(second, true, BigFraction.ONE)
            history.observe(second, false, BigFraction.ONE)
        }

        val selected = history.select(listOf(first, second), Cancellation { true })

        assertEquals(first, selected)
    }
}
