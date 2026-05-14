package com.eignex.klause.bench

import com.eignex.klause.solver.Problem

/** Per-rep `nanoTime` deltas for one backend's three call kinds. */
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
        solvers: List<BenchSolver> = defaultSolvers(problem),
        repetitions: Int = 5,
        sampleCount: Int = 10,
        warmupReps: Int = 2,
    ): BenchmarkReport {
        val timings = solvers.associate { sampler ->
            repeat(warmupReps) {
                sampler.solve()
                sampler.samples(sampleCount)
                sampler.enumerated(sampleCount)
            }
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
