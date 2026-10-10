package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertTrue

class UniGenTest {

    private fun unconstrained(n: Int) =
        Problem(numBoolVars = n, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>())

    private fun projectionKey(s: Sample, n: Int): List<Boolean> = (0 until n).map { s.bools[it] }

    @Test
    fun `accurate sampling on a hashed instance returns valid distinct models`() {
        // 64 models: smallest power of two above the un-hashed cell band (hiThresh ≈ 57), so
        // hashing kicks in at the cheapest enumeration cost. The internal count estimate only
        // seeds the hash depth, so the coarsest ε/δ suffice.
        val p = unconstrained(6)
        val samples = BacktrackSolver(p.bake())
            .samples(
                SamplingConfig(quality = SampleQuality.ACCURATE, seed = 3L, countEpsilon = 2.0, countDelta = 0.99),
                BacktrackParams(),
            )
            .take(12).toList()
        assertTrue(samples.size == 12, "should produce the requested number of accurate samples")
        // Unconstrained, so every assignment is valid; uniformity at 64 cells only checked loosely.
        val distinct = samples.map { projectionKey(it, 6) }.toHashSet().size
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
