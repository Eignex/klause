package com.eignex.klause.solver.count

/**
 * Configuration for approximate model counting ([com.eignex.klause.solver.Solver.approximateCount]).
 *
 * Native counting path: XOR hashes are propagated jointly by Gauss-Jordan elimination (see
 * [com.eignex.klause.solver.factor.GaussianXor]) on the backtrack solver, which keeps hashed-cell
 * enumeration tractable. The result is correct within a multiplicative `(1 ± epsilon)` factor with
 * probability at least `1 - delta`.
 *
 * The projection can include integer variables ([intSamplingSet]): those are channelled to Boolean
 * bits (see [IntBitChannel]) and the hashes range over their bits, so counting is over distinct
 * integer *values* (× the Boolean projection).
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
     * Integer variable ids to count over (their distinct values). Channelled to Boolean bits via
     * [IntBitChannel]; hashes range over the bits. `null` together with a `null` [samplingSet] means "all int vars";
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

/**
 * A model-count answer as an interval `[lower, upper]` that contains the true (projected) count,
 * together with the [confidence] that it does. Produced by both counting entry points on
 * [com.eignex.klause.solver.Solver]:
 *
 *  - [com.eignex.klause.solver.Solver.approximateCount] (ApproxMC) fills a *probabilistic* interval
 *    at [confidence] `1 - δ` around a median [estimate].
 *  - [com.eignex.klause.solver.Solver.exactCount] (anytime exact) fills a *deterministic* interval
 *    ([confidence] `1.0`) that tightens — [lower] only rises, [upper] only falls — until [exact].
 */
data class Count(
    /** Best point estimate of the count. */
    val estimate: Long,
    /** The count is `≥ lower`. For the anytime exact counter this only increases. */
    val lower: Long,
    /** The count is `≤ upper` (`Long.MAX_VALUE` if not yet bounded). For exact counting it only decreases. */
    val upper: Long,
    /** True iff the count is proven exactly (`lower == upper`). */
    val exact: Boolean,
    /** Probability the true count lies in `[lower, upper]`: `1.0` for exact, `1 - δ` for ApproxMC. */
    val confidence: Double,
) {
    /** Multiplicative gap `upper / lower` (1.0 == exact, ∞ when nothing is proven yet). */
    val ratio: Double get() = if (lower <= 0L) Double.POSITIVE_INFINITY else upper.toDouble() / lower.toDouble()
}

/**
 * Configuration for anytime exact (projected) model counting
 * ([com.eignex.klause.solver.Solver.exactCount]). Counting walks the projection variables in a
 * fixed order, proving each partial assignment feasible or not via the solver; the result is a
 * deterministic interval that tightens to exact when the space is fully explored.
 */
data class ExactCountConfig(
    /** Boolean variable ids to count over; see [ApproxCountConfig.samplingSet] for defaulting. */
    val samplingSet: IntArray? = null,
    /** Integer variable ids to count over; see [ApproxCountConfig.intSamplingSet] for defaulting. */
    val intSamplingSet: IntArray? = null,
    /** Overall budget: stop after this many feasibility checks, reporting the current interval. */
    val maxChecks: Long = Long.MAX_VALUE,
    /** Per-check search budget; a check that exceeds it is treated as "possibly feasible" (kept in [Count.upper]). */
    val maxDecisionsPerCheck: Long = 1_000_000L,
    /** Emit a tighter [Count] roughly every this many feasibility checks. */
    val reportEvery: Long = 1_000L,
) {
    init {
        require(maxChecks > 0) { "maxChecks must be positive" }
        require(reportEvery > 0) { "reportEvery must be positive" }
    }
}

/**
 * Configuration for the hybrid [com.eignex.klause.solver.Solver.count]: try exact counting first,
 * fall back to ApproxMC if it doesn't converge within the budget. Carries a single projection used
 * by both phases so their bounds are comparable.
 */
data class CountConfig(
    /** Boolean variable ids to count over; see [ApproxCountConfig.samplingSet] for defaulting. */
    val samplingSet: IntArray? = null,
    /** Integer variable ids to count over; see [ApproxCountConfig.intSamplingSet] for defaulting. */
    val intSamplingSet: IntArray? = null,
    /** Feasibility-check budget for the exact phase before handing off to ApproxMC. */
    val exactBudget: Long = 100_000L,
    /** Per-check search budget within the exact phase. */
    val maxDecisionsPerCheck: Long = 1_000_000L,
    /** ApproxMC multiplicative tolerance for the fallback. */
    val epsilon: Double = 0.8,
    /** ApproxMC failure probability for the fallback. */
    val delta: Double = 0.2,
    /** Seed for the ApproxMC fallback's hash family. */
    val seed: Long? = null,
) {
    internal fun toExactConfig() = ExactCountConfig(
        samplingSet = samplingSet,
        intSamplingSet = intSamplingSet,
        maxChecks = exactBudget,
        maxDecisionsPerCheck = maxDecisionsPerCheck,
        reportEvery = exactBudget, // hybrid only needs the final interval — report once at the end
    )

    internal fun toApproxConfig() = ApproxCountConfig(
        epsilon = epsilon,
        delta = delta,
        samplingSet = samplingSet,
        intSamplingSet = intSamplingSet,
        seed = seed,
    )
}

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
     * Integer variable ids to sample over (their values), channelled to bits and hashed over them.
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
    /**
     * ApproxMC multiplicative tolerance for the internal one-shot count estimate that seeds the
     * hash depth. Coarse is fine for sampling: the estimate only picks the starting cell size,
     * and draws still enforce the `κ` band. Only consulted by [SampleQuality.ACCURATE].
     */
    val countEpsilon: Double = 0.8,
    /** ApproxMC failure probability for the internal count estimate; see [countEpsilon]. */
    val countDelta: Double = 0.2,
) {
    init {
        require(tolerance > 0.0) { "tolerance must be positive, was $tolerance" }
    }
}
