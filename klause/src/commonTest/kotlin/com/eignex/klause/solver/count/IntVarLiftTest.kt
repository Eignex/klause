package com.eignex.klause.solver.count

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Counting and sampling lifted over integer variables (bit-blasted, hashed over their bits). */
class IntVarLiftTest {

    private fun intVars(count: Int, lo: Int, hi: Int) = Problem(
        numBoolVars = 0,
        numIntVars = count,
        intDomains = Array(count) { IntDomain(lo, hi) },
        factors = arrayOf<Factor>(),
    )

    @Test
    fun `exact count over a few integer variables`() {
        // 3 int vars over 0..3 → 4^3 = 64 value combinations, below the hashing threshold.
        val p = intVars(3, 0, 3)
        val r = BacktrackSolver(p).approximateCount(ApproxCountConfig(seed = 0L))
        assertTrue(r.exact, "small integer projection should count exactly")
        assertEquals(64L, r.estimate)
    }

    @Test
    fun `hashed count over integer variables is within the epsilon band`() {
        // 4 int vars over 0..3 → 256 combinations, forces XOR hashing over the bits.
        val p = intVars(4, 0, 3)
        val eps = 0.8
        val r = BacktrackSolver(p).approximateCount(ApproxCountConfig(epsilon = eps, delta = 0.2, seed = 7L))
        assertTrue(!r.exact, "256 combinations should require hashing")
        val lo = 256 / (1.0 + eps)
        val hi = 256 * (1.0 + eps)
        assertTrue(r.estimate in lo.toLong()..hi.toLong(), "estimate ${r.estimate} outside [$lo,$hi]")
    }

    @Test
    fun `count over a mixed bool and int projection`() {
        // 2 bool + 2 int(0..3): 4 (bool) × 16 (int) = 64 combinations.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(),
        )
        val r = BacktrackSolver(p).approximateCount(
            ApproxCountConfig(samplingSet = intArrayOf(0, 1), intSamplingSet = intArrayOf(0, 1), seed = 1L),
        )
        assertTrue(r.exact)
        assertEquals(64L, r.estimate)
    }

    @Test
    fun `accurate sampling over integer values is roughly uniform`() {
        // 2 int vars over 0..3 → 16 value combos, sampled exactly-uniformly (no hashing needed).
        val p = intVars(2, 0, 3)
        val draws = 3200
        val samples = BacktrackSolver(p)
            .samples(SamplingConfig(quality = SampleQuality.ACCURATE, seed = 5L), BacktrackParams())
            .take(draws).toList()
        assertEquals(draws, samples.size)
        val counts = HashMap<List<Int>, Int>()
        for (s in samples) {
            assertTrue(s.ints.all { it in 0..3 }, "sampled value out of domain: ${s.ints.toList()}")
            val key = s.ints.toList()
            counts[key] = (counts[key] ?: 0) + 1
        }
        assertEquals(16, counts.size, "every integer-value combination should be sampled")
        val expected = draws.toDouble() / 16.0
        for ((value, c) in counts) {
            assertTrue(
                c in (expected * 0.5).toInt()..(expected * 1.5).toInt(),
                "combo $value count $c far from uniform expectation $expected",
            )
        }
    }

    @Test
    fun `exact count agrees with full enumeration on a constrained integer problem`() {
        val p = intVars(3, 0, 3)
        val exact = BacktrackSolver(p).enumerate(BacktrackParams())
            .map(::intKey).toHashSet().size.toLong()
        val r = BacktrackSolver(p).approximateCount(ApproxCountConfig(seed = 3L))
        assertEquals(exact, r.estimate)
    }

    private fun intKey(s: Sample): List<Int> = s.ints.toList()
}
