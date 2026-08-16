package com.eignex.klause.bench.tune

/**
 * One suggested config point: an opaque backend [handle] (used to report its result back via
 * [TuningStudy.complete]) plus the decoded parameter [values] — categorical → String, numeric → Double
 * (an integer param arrives as a Double for the caller to round). Backend-agnostic: no tuner-specific
 * types leak through.
 */
internal data class Suggestion(val handle: String, val values: Map<String, Any>)

/**
 * A backend-agnostic hyperparameter tuner — the seam between the ask-tell loop and whatever
 * optimizer sits behind it (OSS Vizier today via [VizierTuner]; a local random/grid fallback or another
 * service later). The loop, [ConfigSpace]s, and eval depend only on this interface and [TuningStudy] /
 * [Suggestion] — no gRPC / protobuf / Vizier type crosses it — so replacing the backend is one new impl.
 */
internal interface Tuner : AutoCloseable {
    /** Open (or reopen) a study named [studyId] that optimizes one objective over [space], [maximize] or
     *  minimize. [noisy] tells the optimizer the reported objectives are noisy estimates (mini-batch
     *  evaluation) so it fits an observation-noise model instead of trusting each measurement exactly. */
    fun openStudy(space: ConfigSpace, maximize: Boolean, studyId: String, noisy: Boolean = false): TuningStudy

    /** Release backend resources (e.g. the gRPC channel). */
    override fun close()
}

/** A live study: ask for [suggest]ions, then tell each one's measured [objective] back via [complete]. */
internal interface TuningStudy : AutoCloseable {
    /** Ask the optimizer for [count] fresh config points. */
    fun suggest(count: Int): List<Suggestion>

    /** Report [suggestion]'s measured [objective] (in the study's orientation). */
    fun complete(suggestion: Suggestion, objective: Double)

    /** Report that [suggestion] could not be evaluated at all — the config threw ([reason] carries the
     *  cause) rather than merely scoring poorly. The optimizer records it as an infeasible trial (no
     *  objective) so it learns to avoid the region *without* a fake reward skewing the response surface;
     *  a config that runs but performs badly is [complete]d with its genuine low reward instead. A
     *  learning-free backend may ignore it. */
    fun markInfeasible(suggestion: Suggestion, reason: String)

    /** Warm-start: inject a config already evaluated *outside* this study (its [values] and measured
     *  [objective]) as a known completed trial, so the optimizer starts already informed. The residual
     *  rounds ([BoTuning]) use this to seed each round's fresh study with every config the earlier
     *  rounds solved — re-scored against the new frontier — for free (no re-evaluation). A learning-free
     *  backend may ignore it. */
    fun observe(values: Map<String, Any>, objective: Double)

    /** Finish with this study (the backend may release its server-side state). */
    override fun close()
}
