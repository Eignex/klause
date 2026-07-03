package com.eignex.klause.localsearch.schedule

import kotlin.random.Random

/**
 * The violation-weight schedule shared by the constraint-gradient strategies: when the search
 * stalls (no strict cost drop for [bumpAfter] steps) it **bumps** every currently-violated factor's
 * weight by [increment] (resistant constraints get heavier, reshaping the gradient) and optionally
 * **relaxes** every weight back toward its seeded baseline (so stale escalations fade and the
 * gradient doesn't grow without bound).
 *
 * It unifies two weight regimes of the same family — `Cbls`'s SAPS-style bump + probabilistic
 * smoothing and `FeasibilityJump`'s bump + geometric decay. Both relaxations are
 * `w ← target + (w − target)·`[relaxKeep] with `target =` [relaxTargetScale]`·base`:
 *
 *  - **FeasibilityJump**: [relaxKeep]`= weightDecay`, [relaxTargetScale]`= 1`, [relaxProbability]`= 1`,
 *    [relaxBeforeBump]`= true` (decay every bump, then escalate).
 *  - **Cbls**: [relaxKeep]`= 1 − smoothFactor`, [relaxTargetScale]`= baseWeight`,
 *    [relaxProbability]`= smoothProb`, [relaxBeforeBump]`= false` (escalate, then smooth with a
 *    probability).
 *
 * As an [AdaptivePolicy] it tracks the stall off the shared per-round feedback channel
 * ([observe]); the weight arrays live in the search state, so the actual mutation is a separate
 * [applyTo] (or the per-step convenience [maintain]) that hands them in. [relaxProbability]`= 0`
 * never draws the RNG, preserving the caller's random stream when smoothing is disabled.
 */
class WeightSchedule(
    /** Steps without a strict cost drop before a bump fires. */
    val bumpAfter: Int = 1,
    /** Weight added to each currently-violated factor on a bump. */
    val increment: Double = 1.0,
    /** Relax retention in `[0, 1]`: each weight keeps this fraction and is pulled the rest of the way
     *  to its target. `1.0` disables relaxing (monotone escalation). */
    val relaxKeep: Double = 1.0,
    /** Scale on the seed baseline that relaxing pulls toward (`target = relaxTargetScale·base`). */
    val relaxTargetScale: Double = 1.0,
    /** Probability a bump is accompanied by a relax pass, in `[0, 1]`. `1.0` always relaxes, `0.0`
     *  never does (and never draws the RNG). */
    val relaxProbability: Double = 1.0,
    /** Whether the relax pass runs before the bump (FeasibilityJump) or after it (Cbls). */
    val relaxBeforeBump: Boolean = true,
) : AdaptivePolicy {
    init {
        require(bumpAfter >= 1) { "bumpAfter must be ≥ 1, got $bumpAfter" }
        require(increment > 0.0) { "increment must be positive, got $increment" }
        require(relaxKeep in 0.0..1.0) { "relaxKeep must be in [0, 1], got $relaxKeep" }
        require(relaxTargetScale >= 0.0) { "relaxTargetScale must be non-negative, got $relaxTargetScale" }
        require(relaxProbability in 0.0..1.0) { "relaxProbability must be in [0, 1], got $relaxProbability" }
    }

    private var lastImprovingStep: Long = -1L
    private var lastSeenStep: Long = -1L
    private var lastCost: Long = Long.MAX_VALUE
    private var pendingBump: Boolean = false

    /** True iff a relax pass can ever run, so callers can skip allocating a snapshot when not. */
    val relaxes: Boolean get() = relaxKeep < 1.0 && relaxProbability > 0.0

    /** Stall-detect off `(step, cost)`; arms a pending bump when the cost has not strictly dropped
     *  for [bumpAfter] steps. A rewound step (restart) re-anchors the trackers to this epoch. */
    fun observeStep(step: Long, cost: Long) {
        if (step < lastSeenStep) {
            lastImprovingStep = step
            lastCost = cost
            lastSeenStep = step
            return
        }
        if (step == lastSeenStep) return
        if (cost < lastCost) {
            lastImprovingStep = step
            lastCost = cost
        } else if (step - lastImprovingStep >= bumpAfter) {
            pendingBump = true
            lastImprovingStep = step
        }
        lastSeenStep = step
    }

    /** Shared-channel hook: stall-detect off the round's step and incumbent cost. */
    override fun observe(round: RoundLog) = observeStep(round.step, round.incumbentCost.toLong())

    /** Apply an armed bump (and its relax pass) to [weights]; no-op when none is pending. [violated]
     *  is the snapshot of currently-violated factor ids, [base] the seed weights, [rng] gates the
     *  probabilistic relax. */
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

    /** The unconditional bump + relax mutation, in the configured order. Public so a strategy can
     *  drive it from its own stall control flow. */
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
        lastImprovingStep = -1L
        lastSeenStep = -1L
        lastCost = Long.MAX_VALUE
        pendingBump = false
    }

    /** Factories for the two known weight regimes, so a strategy names its intent rather than wiring
     *  the raw knobs. */
    companion object {
        /** FeasibilityJump's bump + geometric decay (decay every bump, toward the bare seed). */
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

        /** Cbls's SAPS-style bump + probabilistic smoothing (smooth toward `baseWeight·seed`). */
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
