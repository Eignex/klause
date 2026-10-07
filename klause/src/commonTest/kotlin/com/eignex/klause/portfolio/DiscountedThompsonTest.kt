package com.eignex.klause.portfolio

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class DiscountedThompsonTest {

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
    fun `an arm that earns nothing is almost never chosen`() {
        assertTrue(choicesOf(trained(), arm = 0, rounds = 1_000) < 10)
    }

    @Test
    fun `fading all evidence makes the arms equally likely again`() {
        val bandit = trained()

        bandit.fade(0.0)

        assertTrue(choicesOf(bandit, arm = 0, rounds = 1_000) in 400..600)
    }

    @Test
    fun `a short half-life lets a written-off arm win once its rival stops paying`() {
        val bandit = trained(halfLife = 5.0)

        repeat(50) { bandit.update(1, 0.0, 1.0) }
        repeat(5) { bandit.update(0, 1.0, 1.0) }

        assertTrue(choicesOf(bandit, arm = 0, rounds = 1_000) > 500)
    }

    @Test
    fun `choosing among arms picks the best of them and none outside`() {
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
