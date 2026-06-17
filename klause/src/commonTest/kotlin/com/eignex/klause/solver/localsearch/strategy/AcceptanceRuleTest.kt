package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.schedule.Geometric
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the acceptance axis [AcceptanceRule] (#722): deterministic rules range over both pools,
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

    @Test
    fun `greedy takes the minimum over both pools`() {
        // swap (score-only, -0.5) beats b (noise, 1.0): deterministic rules see both pools.
        assertEquals(swap, AcceptanceRule.Greedy.choose(rng(), listOf(a, b), listOf(swap), score))
    }

    @Test
    fun `walksat noise=1 draws only from the noise pool`() {
        val r = rng()
        repeat(50) {
            val m = AcceptanceRule.WalkSatNoise(1.0).choose(r, listOf(a, b), listOf(swap), score)
            assertTrue(m == a || m == b, "hot noise must pick a noise-eligible move, never the score-only $m")
        }
    }

    @Test
    fun `walksat noise=0 is greedy over both pools`() {
        assertEquals(swap, AcceptanceRule.WalkSatNoise(0.0).choose(rng(), listOf(a, b), listOf(swap), score))
    }

    @Test
    fun `probsat draws from the noise pool and falls back to greedy on the score pool`() {
        val r = rng()
        repeat(50) {
            val m = AcceptanceRule.ProbSat().choose(r, listOf(a, b), listOf(swap), score)
            assertTrue(m == a || m == b, "probSAT roulette must stay in the noise pool, not $m")
        }
        // Empty noise pool → the score-only moves are selected greedily (never roulette-drawn).
        assertEquals(swap, AcceptanceRule.ProbSat().choose(rng(), emptyList(), listOf(swap, flip), score))
    }

    @Test
    fun `skew prefers the smaller move when alpha is large`() {
        // flip (size 1, score 0.0) vs swap (size 2, score -0.5): greedy picks swap; skew(1.0) keys
        // flip=0+1=1.0 vs swap=-0.5+2=1.5, so skew picks the smaller flip.
        assertEquals(swap, AcceptanceRule.Greedy.choose(rng(), listOf(flip), listOf(swap), score))
        assertEquals(flip, AcceptanceRule.Skew(1.0).choose(rng(), listOf(flip), listOf(swap), score))
        // alpha = 0 is exactly greedy.
        assertEquals(swap, AcceptanceRule.Skew(0.0).choose(rng(), listOf(flip), listOf(swap), score))
    }

    @Test
    fun `metropolis stays in the noise pool and falls back to greedy on the score pool`() {
        val r = rng()
        repeat(50) {
            val m = AcceptanceRule.Metropolis(Geometric()).choose(r, listOf(a, b), listOf(swap), score)
            assertTrue(m == a || m == b, "Metropolis must accept from the noise pool, not the score-only $m")
        }
        // Empty noise pool → the score-only moves are selected greedily (never accepted stochastically).
        assertEquals(swap, AcceptanceRule.Metropolis(Geometric()).choose(rng(), emptyList(), listOf(swap, flip), score))
    }

    @Test
    fun `metropolis cools its schedule each call`() {
        val schedule = Geometric(initialTemperature = 1.0, coolingRate = 0.9)
        val t0 = schedule.temperature
        AcceptanceRule.Metropolis(schedule).choose(rng(), listOf(b), emptyList(), score)
        assertTrue(schedule.temperature < t0, "Metropolis must step (cool) the schedule once per call")
    }

    @Test
    fun `all rules return null on empty pools`() {
        for (rule in listOf(
            AcceptanceRule.Greedy,
            AcceptanceRule.WalkSatNoise(0.5),
            AcceptanceRule.ProbSat(),
            AcceptanceRule.Skew(0.3),
            AcceptanceRule.Metropolis(Geometric()),
        )) {
            assertNull(rule.choose(rng(), emptyList(), emptyList(), score), "$rule must be null on empty")
        }
    }
}
