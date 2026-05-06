package com.eignex.klause.bench

import com.eignex.klause.solver.Problem

/**
 * Wall-clock timings for one backend's three call kinds. Each array holds one entry per
 * repetition; callers can compute min/median/mean/etc. as needed. Plain `System.nanoTime()`
 * — adequate for "is one backend an order of magnitude slower" sanity checks. Swap in JMH
 * if/when finer-grained measurements matter.
 */
data class BackendTimings(
    val solveNanos: LongArray,
    val sampleNanos: LongArray,
    val enumerateNanos: LongArray,
)

data class BenchmarkReport(
    val problem: Problem,
    val timings: Map<String, BackendTimings>,
)

object Benchmarker {
    fun bench(
        problem: Problem,
        samplers: List<BenchSampler> = defaultSamplers(problem),
        repetitions: Int = 5,
        sampleCount: Int = 10,
    ): BenchmarkReport {
        val timings = samplers.associate { sampler ->
            val solveTimes = LongArray(repetitions)
            val sampleTimes = LongArray(repetitions)
            val enumTimes = LongArray(repetitions)
            for (rep in 0 until repetitions) {
                solveTimes[rep] = timeIt { sampler.solve() }
                sampleTimes[rep] = timeIt { sampler.samples(sampleCount) }
                enumTimes[rep] = timeIt { sampler.enumerated(sampleCount) }
            }
            sampler.name to BackendTimings(solveTimes, sampleTimes, enumTimes)
        }
        return BenchmarkReport(problem, timings)
    }

    private inline fun timeIt(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return System.nanoTime() - t0
    }
}
