package com.eignex.klause.portfolio

import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.SumResult
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
    fun `credit owed to an idle arm counts against the arm being scored`() {
        val ledger = RewardLedger(2)
        ledger.credit(1, Signal.ClauseUses, 5.0)
        ledger.credit(0, Signal.Improvement, 4.0)

        assertEquals(0.5, ledger.settle(0, 100), 1e-9)
    }

    @Test
    fun `an arm is not scored on a signal it cannot earn`() {
        // Arm 0 is backtrack, arm 1 local search: only arm 1 can lower violation.
        val ledger = RewardLedger(2) { arm, signal -> signal != Signal.Violation || arm == 1 }
        ledger.credit(1, Signal.Violation, 0.5)
        ledger.settle(1, 100)
        ledger.credit(0, Signal.RootFixings, 3.0)

        assertEquals(1.0, ledger.settle(0, 100), 1e-9)
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

    private fun stats(rootFixed: Double = Double.NEGATIVE_INFINITY, violation: Double = Double.NaN) = SolveStats(
        search = SearchStats(rootFixed = MaxResult(rootFixed)),
        ls = LocalSearchStats(incumbentViolation = violation),
    )

    @Test
    fun `root fixings an arm already showed earn nothing again`() {
        val ledger = RewardLedger(1)
        val progress = ProgressCredit(1)
        progress.observe(ledger, 0, stats(rootFixed = 5.0))
        ledger.settle(0, 100)

        progress.observe(ledger, 0, stats(rootFixed = 5.0))

        assertEquals(0.0, ledger.settle(0, 100))
    }

    @Test
    fun `glue clauses earn only their growth`() {
        val ledger = RewardLedger(1)
        val progress = ProgressCredit(1)
        val search = SearchStats(glueClauses = SumResult(3.0))
        progress.observe(ledger, 0, SolveStats(search = search))
        ledger.settle(0, 100)

        progress.observe(ledger, 0, SolveStats(search = search))

        assertEquals(mapOf("Glue" to 3.0), ledger.creditOf(0))
    }

    @Test
    fun `only lowering the record violation earns credit`() {
        val ledger = RewardLedger(3)
        val progress = ProgressCredit(3)
        progress.observe(ledger, 0, stats(violation = 8.0))
        ledger.settle(0, 100)

        progress.observe(ledger, 1, stats(violation = 8.0))
        progress.observe(ledger, 2, stats(violation = 2.0))

        assertEquals(0.0, ledger.settle(1, 100))
        assertEquals(1.0, ledger.settle(2, 100))
    }
}
