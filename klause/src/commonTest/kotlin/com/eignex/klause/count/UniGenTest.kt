package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UniGenTest {

    private fun unconstrained(n: Int) =
        Problem(numBoolVars = n, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>())

    private fun projectionKey(s: Sample, n: Int): List<Boolean> = (0 until n).map { s.bools[it] }

    @Test
    fun `cheap sampling returns valid models`() {
        // (x0 v x1): the all-false assignment is the only invalid one over 3 vars.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val samples = BacktrackSolver(p.bake())
            .samples(SamplingConfig(quality = SampleQuality.CHEAP, seed = 1L), BacktrackParams(randomSeed = 1L))
            .take(20).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) assertTrue(s.bools[0] || s.bools[1], "cheap sample violated the clause")
    }

    @Test
    fun `accurate sampling on a small instance is exactly uniform`() {
        // 4 free vars, one clause -> 12 models, all within a single (un-hashed) cell.
        val n = 4
        val p = Problem(
            numBoolVars = n,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val draws = 2400
        val counts = HashMap<List<Boolean>, Int>()
        val samples = BacktrackSolver(p.bake())
            .samples(SamplingConfig(quality = SampleQuality.ACCURATE, seed = 7L), BacktrackParams())
            .take(draws).toList()

        assertEquals(draws, samples.size)
        for (s in samples) {
            assertTrue(s.bools[0] || s.bools[1], "accurate sample violated the clause")
            counts[projectionKey(s, n)] = (counts[projectionKey(s, n)] ?: 0) + 1
        }
        // All 12 satisfying assignments should appear, each near the uniform expectation.
        assertEquals(12, counts.size, "every satisfying assignment should be sampled")
        val expected = draws.toDouble() / 12.0
        for ((model, c) in counts) {
            assertTrue(
                c in (expected * 0.5).toInt()..(expected * 1.5).toInt(),
                "model $model count $c far from uniform expectation $expected",
            )
        }
    }

    @Test
    fun `accurate sampling on a hashed instance returns valid distinct models`() {
        // 128 models: smallest power of two above the un-hashed cell band (hiThresh ≈ 57), so
        // hashing kicks in at the cheapest enumeration cost. The internal count estimate only
        // seeds the hash depth, so the coarsest ε/δ suffice.
        val p = unconstrained(7)
        val samples = BacktrackSolver(p.bake())
            .samples(
                SamplingConfig(quality = SampleQuality.ACCURATE, seed = 3L, countEpsilon = 2.0, countDelta = 0.99),
                BacktrackParams(),
            )
            .take(12).toList()
        assertTrue(samples.size == 12, "should produce the requested number of accurate samples")
        // Unconstrained, so every assignment is valid; uniformity at 128 cells only checked loosely.
        val distinct = samples.map { projectionKey(it, 7) }.toHashSet().size
        assertTrue(distinct >= 6, "expected good spread, got $distinct distinct out of 12")
    }

    @Test
    fun `accurate sampling on unsat yields nothing`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val samples = BacktrackSolver(p.bake())
            .samples(SamplingConfig(quality = SampleQuality.ACCURATE, seed = 0L), BacktrackParams())
            .take(5).toList()
        assertTrue(samples.isEmpty(), "UNSAT instance should yield no accurate samples")
    }
}
