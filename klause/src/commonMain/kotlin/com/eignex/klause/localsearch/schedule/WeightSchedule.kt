package com.eignex.klause.localsearch.schedule

import kotlin.random.Random

/**
 * The violation-weight schedule of the constraint-gradient strategies: after [bumpAfter] steps without a
 * strict cost drop, every violated factor's weight gains [increment], and every weight may relax toward its
 * seed by `w ← target + (w − target)·relaxKeep` with `target = relaxTargetScale·base`. A lower unweighted
 * Cumulative fraction at equal cost also counts as progress.
 *
 * Covers bump with probabilistic smoothing (after F. Hutter, D. A. D. Tompkins, H. H. Hoos, "Scaling and
 * probabilistic smoothing: efficient dynamic local search for SAT", CP 2002,
 * https://doi.org/10.1007/3-540-46135-3_16) and bump with geometric decay. The weight arrays live in the
 * search state, so [applyTo] hands them in. A zero [relaxProbability] never draws the RNG.
 */
class WeightSchedule(
    /** Steps without a strict cost drop before a bump fires. */
    val bumpAfter: Int = 1,
    /** Weight added to each currently-violated factor on a bump. */
    val increment: Double = 1.0,
    /** Fraction of its distance to the target each weight keeps on a relax; `1.0` disables relaxing. */
    val relaxKeep: Double = 1.0,
    /** Scale on the seed baseline that relaxing pulls toward (`target = relaxTargetScale·base`). */
    val relaxTargetScale: Double = 1.0,
    /** Probability a bump is accompanied by a relax pass, in `[0, 1]`. */
    val relaxProbability: Double = 1.0,
    /** Whether the relax pass runs before the bump or after it. */
    val relaxBeforeBump: Boolean = true,
) : AdaptivePolicy {
    init {
        require(bumpAfter >= 1) { "bumpAfter must be ≥ 1, got $bumpAfter" }
        require(increment > 0.0) { "increment must be positive, got $increment" }
        require(relaxKeep in 0.0..1.0) { "relaxKeep must be in [0, 1], got $relaxKeep" }
        require(relaxTargetScale >= 0.0) { "relaxTargetScale must be non-negative, got $relaxTargetScale" }
        require(relaxProbability in 0.0..1.0) { "relaxProbability must be in [0, 1], got $relaxProbability" }
    }

    private val progress = ProgressWindow(initialProgressStep = -1L, trackFraction = true)
    private var pendingBump: Boolean = false

    /** Whether a relax pass can ever run. */
    val relaxes: Boolean get() = relaxKeep < 1.0 && relaxProbability > 0.0

    /** Arms a pending bump after [bumpAfter] steps without a strict cost drop; a rewound step re-anchors. */
    fun observeStep(step: Long, cost: Long) = observeStep(step, cost, 0.0)

    internal fun observeStep(step: Long, cost: Long, fraction: Double) {
        val observation = progress.observe(step, cost, fraction)
        if (observation == ProgressObservation.NonImproving && progress.elapsed(step) >= bumpAfter) {
            pendingBump = true
            progress.restartAt(step)
        }
    }

    override fun observe(round: RoundLog) = observeStep(round.step, round.incumbentCost.toLong())

    /** Apply an armed bump and its relax pass; [violated] holds the violated factor ids, [base] the seeds. */
    fun applyTo(weights: DoubleArray, base: DoubleArray, violated: IntArray, rng: Random) {
        if (!pendingBump) return
        pendingBump = false
        bumpAndRelax(weights, base, violated, rng)
    }

    /** Per-step convenience for strategies that maintain weights inline: stall-detect then apply. */
    fun maintain(step: Long, cost: Long, weights: DoubleArray, base: DoubleArray, violated: IntArray, rng: Random) {
        observeStep(step, cost)
        applyTo(weights, base, violated, rng)
    }

    /** The unconditional bump and relax, for strategies with their own stall control flow. */
    fun bumpAndRelax(weights: DoubleArray, base: DoubleArray, violated: IntArray, rng: Random) {
        val relaxNow = relaxKeep < 1.0 && relaxProbability > 0.0 &&
            (relaxProbability >= 1.0 || rng.nextDouble() < relaxProbability)
        if (relaxBeforeBump && relaxNow) relax(weights, base)
        for (fid in violated) weights[fid] += increment
        if (!relaxBeforeBump && relaxNow) relax(weights, base)
    }

    private fun relax(weights: DoubleArray, base: DoubleArray) {
        for (i in weights.indices) {
            val target = relaxTargetScale * base[i]
            weights[i] = target + (weights[i] - target) * relaxKeep
        }
    }

    override fun reset() {
        progress.reset()
        pendingBump = false
    }

    /** Factories for the two known weight regimes. */
    companion object {
        /** Bump with geometric decay toward the bare seed, decaying before every bump. */
        fun feasibilityJump(
            weightBumpAfter: Int = 1,
            weightIncrement: Double = 1.0,
            weightDecay: Double = 0.999,
        ): WeightSchedule = WeightSchedule(
            bumpAfter = weightBumpAfter,
            increment = weightIncrement,
            relaxKeep = weightDecay,
            relaxTargetScale = 1.0,
            relaxProbability = 1.0,
            relaxBeforeBump = true,
        )

        /** SAPS-style bump with probabilistic smoothing toward `baseWeight·seed`. */
        fun cbls(
            stallSteps: Int = 1,
            stallIncrement: Double = 1.0,
            smoothProb: Double = 0.0,
            smoothFactor: Double = 0.8,
            baseWeight: Double = 1.0,
        ): WeightSchedule = WeightSchedule(
            bumpAfter = stallSteps,
            increment = stallIncrement,
            relaxKeep = 1.0 - smoothFactor,
            relaxTargetScale = baseWeight,
            relaxProbability = smoothProb,
            relaxBeforeBump = false,
        )
    }
}
