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
    fun `greedy takes the minimum over both pools`() {
        // swap (score-only, -0.5) beats b (noise, 1.0): deterministic rules see both pools.
        assertEquals(swap, AcceptanceRule.Greedy.choose(rng(), listOf(a, b), listOf(swap), anyTemp, score))
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
    fun `walksat noise=0 is greedy over both pools`() {
        assertEquals(swap, AcceptanceRule.WalkSatNoise(0.0).choose(rng(), listOf(a, b), listOf(swap), anyTemp, score))
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
    fun `skew prefers the smaller move when alpha is large`() {
        // flip (size 1, score 0.0) vs swap (size 2, score -0.5): greedy picks swap; skew(1.0) keys
        // flip=0+1=1.0 vs swap=-0.5+2=1.5, so skew picks the smaller flip.
        assertEquals(swap, AcceptanceRule.Greedy.choose(rng(), listOf(flip), listOf(swap), anyTemp, score))
        assertEquals(flip, AcceptanceRule.Skew(1.0).choose(rng(), listOf(flip), listOf(swap), anyTemp, score))
        // alpha = 0 is exactly greedy.
        assertEquals(swap, AcceptanceRule.Skew(0.0).choose(rng(), listOf(flip), listOf(swap), anyTemp, score))
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
    fun `metropolis accepts an improving move regardless of temperature`() {
        // b has delta 1.0 (worsening) and the only other noise option is a (2.0); at a near-zero
        // temperature the worsening test almost never passes, so the rule takes its random fallback —
        // still a noise-pool move. The acceptance reads the supplied temperature, owning no schedule.
        val improving = Move.IntSet(4, 1) // delta -1.0
        val improvingScore: (Move) -> Double = { if (it == improving) -1.0 else scores.getValue(it) }
        val m = AcceptanceRule.Metropolis.choose(rng(), listOf(improving), emptyList(), 1e-6, improvingScore)
        assertEquals(improving, m, "an improving move (delta ≤ 0) is always accepted, even when cold")
    }

    @Test
    fun `all rules return null on empty pools`() {
        for (rule in listOf(
            AcceptanceRule.Greedy,
            AcceptanceRule.WalkSatNoise(0.5),
            AcceptanceRule.ProbSat(),
            AcceptanceRule.Skew(0.3),
            AcceptanceRule.Metropolis,
        )) {
            assertNull(rule.choose(rng(), emptyList(), emptyList(), anyTemp, score), "$rule must be null on empty")
        }
    }
}
