package com.eignex.klause.solver.count

/**
 * Configuration for approximate model counting ([com.eignex.klause.solver.Solver.approximateCount]).
 *
 * Native counting path: XOR hashes are propagated jointly by Gauss-Jordan elimination (see
 * [com.eignex.klause.solver.factor.GaussianXor]) on the backtrack solver, which keeps hashed-cell
 * enumeration tractable. The result is correct within a multiplicative `(1 ± epsilon)` factor with
 * probability at least `1 - delta`.
 *
 * The projection can include integer variables ([intSamplingSet]): those are bit-blasted and the
 * hashes range over their bits, so counting is over distinct integer *values* (× the Boolean
 * projection).
 */
data class ApproxCountConfig(
    /** Multiplicative tolerance `ε`. Smaller ε → tighter bound → more enumeration work. */
    val epsilon: Double = 0.8,
    /** Failure probability `δ`. Smaller δ → more independent runs (median). */
    val delta: Double = 0.2,
    /**
     * Boolean variable ids to count over. When both this and [intSamplingSet] are `null`, the
     * projection defaults to *all* variables (every Boolean and every integer var). When either is
     * non-`null`, only the listed variables are counted. Counting is over *distinct projections*.
     */
    val samplingSet: IntArray? = null,
    /**
     * Integer variable ids to count over (their distinct values). Bit-blasted via `BitBlaster`;
     * hashes range over the bits. `null` together with a `null` [samplingSet] means "all int vars";
     * `null` with a non-`null` [samplingSet] means "no int vars".
     */
    val intSamplingSet: IntArray? = null,
    /** Seed for the hash-family RNG; `null` draws a fresh seed. Fix it for reproducibility. */
    val seed: Long? = null,
) {
    init {
        require(epsilon > 0.0) { "epsilon must be positive, was $epsilon" }
        require(delta > 0.0 && delta < 1.0) { "delta must be in (0,1), was $delta" }
    }
}

/** Result of [com.eignex.klause.solver.Solver.approximateCount]. */
data class ApproxCount(
    /** The (median) estimated number of distinct projected models. */
    val estimate: Long,
    /** The `ε` the estimate is guaranteed within (multiplicatively). */
    val epsilon: Double,
    /** The `δ` failure probability the guarantee holds with probability `1 - δ`. */
    val delta: Double,
    /** Number of independent ApproxMC runs performed. */
    val iterations: Int,
    /** True when the count was obtained exactly (problem small enough to enumerate fully). */
    val exact: Boolean,
)

/** Quality tier for [com.eignex.klause.solver.Solver.samples]. */
enum class SampleQuality {
    /**
     * The production path: fast, biased draws from the backend's own [com.eignex.klause.solver.Solver.samples]
     * (search-order / locally-reachable). No uniformity guarantee.
     */
    CHEAP,

    /**
     * Near-uniform draws via XOR-hashing (UniGen2). An accuracy-validation tool: far more
     * expensive than [CHEAP], used to check how biased the cheap path is.
     */
    ACCURATE,
}

/**
 * Configuration for [com.eignex.klause.solver.Solver.samples] (the quality-tiered overload).
 * Defaults select the cheap production path.
 */
data class SamplingConfig(
    /** Cheap (default) or accurate near-uniform. */
    val quality: SampleQuality = SampleQuality.CHEAP,
    /**
     * Boolean variable ids to sample over (the projection). With a `null` [intSamplingSet] too, the
     * projection defaults to all variables. Only consulted by [SampleQuality.ACCURATE].
     */
    val samplingSet: IntArray? = null,
    /**
     * Integer variable ids to sample over (their values), bit-blasted and hashed over their bits.
     * Defaulting matches [ApproxCountConfig.intSamplingSet]. Only consulted by [SampleQuality.ACCURATE].
     */
    val intSamplingSet: IntArray? = null,
    /** Seed for the hash-family RNG; `null` draws a fresh seed. */
    val seed: Long? = null,
    /**
     * UniGen2 tolerance `κ` (> 0): sets the target cell-size band. Smaller κ → tighter, more
     * uniform, more expensive. Only consulted by [SampleQuality.ACCURATE].
     */
    val tolerance: Double = 0.5,
) {
    init {
        require(tolerance > 0.0) { "tolerance must be positive, was $tolerance" }
    }
}
