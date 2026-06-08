package com.eignex.klause.bench.metric

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/** Per-phase nanos for a propagation-heavy workload. */
data class PropagationTimings(
    internal val bakeNanos: Long,
    internal val oneShotPinNanos: Long,
    internal val incrementalPinNanos: Long,
    internal val pinCount: Int,
)

/**
 * Microbenchmark targeted at the propagation hot path, isolated from LS / SAT-solver
 * overhead: bake-time, one-shot with assumptions, and incremental (session) pinning.
 */
object PropagationMetric {
    internal fun bench(
        problem: Problem,
        pinCount: Int = 10,
        repetitions: Int = 50,
        warmupReps: Int = 10,
        seed: Long = 0L,
    ): PropagationTimings {
        val rng = Random(seed)
        val baked = problem.baked
        val baseBools: Map<Int, Boolean> = if (baked is PropagationResult.Implied) baked.bools else emptyMap()
        val freeBools = (0 until problem.numBoolVars).filter { it !in baseBools }
        val pins: List<Pair<Int, Boolean>> = (0 until pinCount).map {
            val v = freeBools[rng.nextInt(freeBools.size.coerceAtLeast(1))]
            v to rng.nextBoolean()
        }
        val asm = Assumptions(bools = pins.toMap())

        repeat(warmupReps) {
            problem.propagate()
            problem.propagate(asm)
            runSessionChain(problem, pins)
        }

        val bakeNs = repeatTimed(repetitions) { problem.propagate() }
        val oneShotNs = repeatTimed(repetitions) { problem.propagate(asm) }
        val incrementalNs = repeatTimed(
            repetitions,
        ) { runSessionChain(problem, pins) } / pinCount.toLong().coerceAtLeast(1L)

        return PropagationTimings(bakeNs, oneShotNs, incrementalNs, pinCount)
    }

    private fun runSessionChain(problem: Problem, pins: List<Pair<Int, Boolean>>) {
        val s = PropagationSession(problem)
        s.seed(Assumptions.None)
        for ((v, b) in pins) s.pinBool(v, b)
    }

    private inline fun repeatTimed(n: Int, block: () -> Unit): Long {
        val t0 = System.nanoTime()
        repeat(n) { block() }
        return (System.nanoTime() - t0) / n
    }
}
