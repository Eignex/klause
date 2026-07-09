package com.eignex.klause.bench.tune

import kotlin.random.Random

/**
 * A local [Tuner] that draws each suggestion uniformly at random from the space ([ConfigSpace.sample])
 * and learns nothing from the reported objectives. It is the Docker-free fallback — no Vizier service
 * needed — so the ask-tell loop is unit-testable and runnable offline, and it doubles as the
 * random-search baseline a Bayesian optimizer must beat. Deterministic from [seed].
 *
 * Being a second [Tuner] implementation, it also proves the seam: swapping the optimizer backend is a
 * new class, nothing else changes.
 */
internal class RandomTuner(seed: Long = 0L) : Tuner {
    private val rng = Random(seed)
    private var counter = 0

    override fun openStudy(space: ConfigSpace, maximize: Boolean, studyId: String, noisy: Boolean): TuningStudy =
        object : TuningStudy {
            override fun suggest(count: Int): List<Suggestion> =
                List(count) { Suggestion(handle = "$studyId/random-${counter++}", values = space.sample(rng)) }

            /** Random search does not learn, so a reported objective is ignored. */
            override fun complete(suggestion: Suggestion, objective: Double) = Unit

            /** Random search does not learn, so an infeasible trial is ignored. */
            override fun markInfeasible(suggestion: Suggestion, reason: String) = Unit

            /** Random search does not learn, so a warm-start observation is ignored. */
            override fun observe(values: Map<String, Any>, objective: Double) = Unit

            override fun close() = Unit
        }

    override fun close() = Unit
}
