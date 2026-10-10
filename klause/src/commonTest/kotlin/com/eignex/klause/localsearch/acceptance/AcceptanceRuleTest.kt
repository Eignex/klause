package com.eignex.klause.localsearch.acceptance

import com.eignex.klause.localsearch.Move
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the acceptance axis [AcceptanceRule]: deterministic rules range over both pools,
 * stochastic rules draw from the noise pool only (never the score-only pool), and all return null on
 * empty input.
 */
class AcceptanceRuleTest {

    private val a = Move.IntSet(0, 1) // noise-eligible, score 2.0
    private val b = Move.IntSet(1, 1) // noise-eligible, score 1.0 (the greedy winner)
    private val swap = Move.Compound(listOf(Move.IntSet(2, 1), Move.IntSet(3, 1))) // score-only, score -0.5
    private val flip = Move.BoolFlip(0) // noise-eligible, score 0.0 (small, for skew)

    private val scores = mapOf(a to 2.0, b to 1.0, swap to -0.5, flip to 0.0)
    private val score: (Move) -> Double = { scores.getValue(it) }

    private fun rng() = Random(7)

    // Acceptance is pure with respect to the schedule axis: only Metropolis reads the temperature,
    // the others ignore it, so a fixed value suffices for the non-Metropolis cases.
    private val anyTemp = 1.0

    @Test
    fun `greedy descent returns null at a local optimum`() {
        // No candidate strictly improves (the minimum is flip at 0.0, not < 0): a local optimum, so the
        // rule declines rather than committing a non-improving move.
        assertNull(AcceptanceRule.GreedyDescent.choose(rng(), listOf(a, b), listOf(flip), anyTemp, score))
    }

    @Test
    fun `walksat noise=1 draws only from the noise pool`() {
        val r = rng()
        repeat(50) {
            val m = AcceptanceRule.WalkSatNoise(1.0).choose(r, listOf(a, b), listOf(swap), anyTemp, score)
            assertTrue(m == a || m == b, "hot noise must pick a noise-eligible move, never the score-only $m")
        }
    }

    @Test
    fun `probsat draws from the noise pool and falls back to greedy on the score pool`() {
        val r = rng()
        repeat(50) {
            val m = AcceptanceRule.ProbSat().choose(r, listOf(a, b), listOf(swap), anyTemp, score)
            assertTrue(m == a || m == b, "probSAT roulette must stay in the noise pool, not $m")
        }
        // Empty noise pool → the score-only moves are selected greedily (never roulette-drawn).
        assertEquals(swap, AcceptanceRule.ProbSat().choose(rng(), emptyList(), listOf(swap, flip), anyTemp, score))
    }

    @Test
    fun `metropolis stays in the noise pool and falls back to greedy on the score pool`() {
        val r = rng()
        repeat(50) {
            val m = AcceptanceRule.Metropolis.choose(r, listOf(a, b), listOf(swap), anyTemp, score)
            assertTrue(m == a || m == b, "Metropolis must accept from the noise pool, not the score-only $m")
        }
        // Empty noise pool → the score-only moves are selected greedily (never accepted stochastically).
        assertEquals(swap, AcceptanceRule.Metropolis.choose(rng(), emptyList(), listOf(swap, flip), anyTemp, score))
    }

    @Test
    fun `all rules return null on empty pools`() {
        for (rule in listOf(
            AcceptanceRule.Greedy,
            AcceptanceRule.GreedyDescent,
            AcceptanceRule.WalkSatNoise(0.5),
            AcceptanceRule.ProbSat(),
            AcceptanceRule.Skew(0.3),
            AcceptanceRule.Metropolis,
        )) {
            assertNull(rule.choose(rng(), emptyList(), emptyList(), anyTemp, score), "$rule must be null on empty")
        }
    }
}
