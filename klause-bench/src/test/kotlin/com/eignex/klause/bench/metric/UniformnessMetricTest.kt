package com.eignex.klause.bench.metric

import com.eignex.klause.bench.InProcessSolver
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UniformnessMetricTest {

    private class FixedSamples(override val problem: Problem, private val list: List<Sample>) : InProcessSolver {
        override val name = "fixed"
        override fun enumerateSequence(): Sequence<Sample> = list.asSequence()
        override fun samplesSequence(): Sequence<Sample> = list.asSequence()
    }

    @Test
    fun `streamed stats match the closed-form values on a 50-50 two-sample stream`() {
        // Two distinct samples over 2 unconstrained bools, repeated 50/50 over 100 draws.
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyList())
        val s1 = Sample(booleanArrayOf(false, false), IntArray(0))
        val s2 = Sample(booleanArrayOf(true, true), IntArray(0))
        val samples = (0 until 100).map { if (it % 2 == 0) s1 else s2 }

        val r = UniformnessMetric.analyse("t", FixedSamples(problem, samples), sampleCount = 100)

        assertEquals(2, r.distinctCount)
        assertEquals(0.02, r.distinctnessRatio)
        // One distinct pair at Hamming distance 2: every spread statistic collapses onto it.
        assertEquals(2.0, r.meanPairwiseHamming)
        assertEquals(2.0, r.pairwiseHammingP5)
        assertEquals(2.0, r.pairwiseHammingP95)
        // 50/50 split → entropy ln 2.
        assertTrue(abs(r.sampleEntropy - ln(2.0)) < 1e-9, "entropy ${r.sampleEntropy}")
        // HLL estimate of 2 distinct hashes is essentially exact at this cardinality.
        val est = assertNotNull(r.distinctEstimate)
        assertTrue(abs(est - 2.0) < 0.1, "hll estimate $est")
        // Oracle: 4 feasible assignments, 2 seen → coverage 0.5; KL = ln 2 for the 50/50 split.
        assertEquals(0.5, r.coverageFraction)
        assertTrue(abs((r.klFromUniform ?: 0.0) - ln(2.0)) < 1e-9, "kl ${r.klFromUniform}")
    }
}
