package com.eignex.klause.portfolio

import com.eignex.kumulant.bandit.univariate.BetaBernoulliTS
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.stat.summary.BernoulliSumResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscountedThompsonTest {

    @Test
    fun `weighted rewards and phase fading should preserve seeded posterior choices`() {
        val bandit = DiscountedThompson(4, Random(7), Double.POSITIVE_INFINITY)
        val reference = MultiArmedBandit(4, BetaBernoulliTS(), Random(7))
        val candidates = listOf(0, 2, 3)
        for (retained in listOf(0.25, 1.0, 0.0, 0.5)) {
            repeat(16) { step ->
                val arm = step % 4
                val value = (step % 5) / 4.0
                val weight = (step % 3) / 2.0
                bandit.update(arm, value, weight)
                reference.update(arm, value, weight)
            }

            bandit.fade(retained)
            val evidence = reference.snapshot().map {
                BernoulliSumResult(1.0 + (it.successes - 1.0) * retained,
                    2.0 + (it.trials - 2.0) * retained)
            }
            reference.reset()
            reference.merge(evidence)

            repeat(16) {
                assertEquals(reference.choose(), bandit.choose())
                val snapshot = reference.snapshot()
                val expected = candidates.maxBy { arm ->
                    reference.random.nextBeta(snapshot[arm].successes,
                        snapshot[arm].trials - snapshot[arm].successes)
                }
                assertEquals(expected, bandit.chooseAmong(candidates))
            }
        }

        bandit.reset()
        reference.reset()

        repeat(16) { assertEquals(reference.choose(), bandit.choose()) }
    }

    private fun choicesOf(bandit: DiscountedThompson, arm: Int, rounds: Int): Int =
        (0 until rounds).count { bandit.choose() == arm }

    private fun trained(halfLife: Double = 1_000.0): DiscountedThompson {
        val bandit = DiscountedThompson(2, Random(7), halfLife)
        repeat(200) {
            bandit.update(0, 0.0, 1.0)
            bandit.update(1, 1.0, 1.0)
        }
        return bandit
    }

    @Test
    fun `an arm that earns nothing should almost never be chosen`() {
        assertTrue(choicesOf(trained(), arm = 0, rounds = 1_000) < 10)
    }

    @Test
    fun `fading all evidence should make the arms equally likely again`() {
        val bandit = trained()

        bandit.fade(0.0)

        assertTrue(choicesOf(bandit, arm = 0, rounds = 1_000) in 400..600)
    }

    @Test
    fun `a short half-life should let a written-off arm win once its rival stops paying`() {
        val bandit = trained(halfLife = 5.0)

        repeat(50) { bandit.update(1, 0.0, 1.0) }
        repeat(5) { bandit.update(0, 1.0, 1.0) }

        assertTrue(choicesOf(bandit, arm = 0, rounds = 1_000) > 500)
    }

    @Test
    fun `choosing among arms should pick the best of them and none outside`() {
        val bandit = DiscountedThompson(3, Random(7), 1_000.0)
        repeat(200) {
            bandit.update(0, 0.0, 1.0)
            bandit.update(1, 1.0, 1.0)
            bandit.update(2, 0.5, 1.0)
        }

        val choices = List(1_000) { bandit.chooseAmong(listOf(0, 2)) }

        assertTrue(choices.none { it == 1 } && choices.count { it == 2 } > 990)
    }
}
