package com.eignex.klause.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewardLedgerTest {

    @Test
    fun `an arm earning as fast as the rest of the pool scores one half`() {
        val ledger = RewardLedger(2)
        ledger.credit(0, Signal.Improvement, 4.0)
        ledger.settle(0, 100)

        ledger.credit(1, Signal.Improvement, 4.0)

        assertEquals(0.5, ledger.settle(1, 100), 1e-9)
    }

    @Test
    fun `the only arm earning a signal scores one`() {
        val ledger = RewardLedger(2)
        ledger.settle(0, 100)

        ledger.credit(1, Signal.Improvement, 4.0)

        assertEquals(1.0, ledger.settle(1, 100), 1e-9)
    }

    @Test
    fun `an arm that earned nothing scores zero`() {
        val ledger = RewardLedger(2)
        ledger.credit(0, Signal.Improvement, 4.0)
        ledger.settle(0, 100)

        assertEquals(0.0, ledger.settle(1, 100))
    }

    @Test
    fun `the same credit over more work scores lower`() {
        fun rewardFor(work: Long): Double {
            val ledger = RewardLedger(2)
            ledger.credit(0, Signal.Improvement, 4.0)
            ledger.settle(0, 100)
            ledger.credit(1, Signal.Improvement, 4.0)
            return ledger.settle(1, work)
        }

        assertTrue(rewardFor(400) < rewardFor(100))
    }

    @Test
    fun `credit posted to an idle arm settles at its next segment`() {
        val ledger = RewardLedger(2)
        ledger.credit(0, Signal.Improvement, 4.0)
        ledger.settle(0, 100)
        ledger.credit(1, Signal.Improvement, 4.0)

        ledger.settle(0, 100)

        assertTrue(ledger.settle(1, 100) > 0.0)
    }

    @Test
    fun `scaling a signal does not change the reward`() {
        fun rewardAt(scale: Double): Double {
            val ledger = RewardLedger(2)
            ledger.credit(0, Signal.Improvement, 3.0 * scale)
            ledger.settle(0, 100)
            ledger.credit(1, Signal.Improvement, 5.0 * scale)
            return ledger.settle(1, 100)
        }

        assertEquals(rewardAt(1.0), rewardAt(1_000.0), 1e-9)
    }

    @Test
    fun `a new phase forgets the pooled rates`() {
        val ledger = RewardLedger(2)
        ledger.credit(0, Signal.Improvement, 1_000.0)
        ledger.settle(0, 100)

        ledger.resetPhase()
        ledger.credit(1, Signal.Improvement, 1.0)

        assertEquals(1.0, ledger.settle(1, 100), 1e-9)
    }
}
