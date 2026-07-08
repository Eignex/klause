package com.eignex.klause.bench.tune

import com.eignex.klause.bench.metric.ReferenceStore
import com.eignex.klause.bench.runner.ResolvedProblem
import kotlin.random.Random

/** Stable per-instance identity across the corpus (a bare name isn't unique across suites). */
internal fun instanceKey(p: ResolvedProblem): String = "${ReferenceStore.suiteOf(p.ref)}/${p.name}"

/**
 * The sampling universe the BO draws mini-batches from (#35). [sample] yields a handful of resolved
 * problems per call — the pool may be far larger than any batch, and a [StratifiedPool] resolves only
 * the drawn refs (lazy, so a huge pool is never materialised). [stratumOf] buckets an instance for the
 * coverage frontier, which is what keeps the residual signal alive on a large pool: with a per-instance
 * frontier a small batch touches too few instances, but a stratum frontier (a handful of buckets) is
 * always covered. [UniformPool] is the materialised, per-instance-stratum fallback for tests.
 */
internal interface SamplingPool {
    /** A mini-batch of up to [size] distinct problems, resolved. */
    fun sample(size: Int, rng: Random): List<ResolvedProblem>

    /** The coverage-frontier bucket for [p]. */
    fun stratumOf(p: ResolvedProblem): String

    /** Whether the pool has anything to draw. */
    fun isNotEmpty(): Boolean
}

/** A materialised pool sampled uniformly, each instance its own stratum — so the frontier is
 *  per-instance (the pre-#35 behaviour), used by the tests and as a fallback. */
internal class UniformPool(private val instances: List<ResolvedProblem>) : SamplingPool {
    override fun sample(size: Int, rng: Random): List<ResolvedProblem> =
        if (instances.size <= size) instances else instances.indices.shuffled(rng).take(size).map { instances[it] }

    override fun stratumOf(p: ResolvedProblem): String = instanceKey(p)

    override fun isNotEmpty(): Boolean = instances.isNotEmpty()
}
