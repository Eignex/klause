package com.eignex.klause.backtrack.lp

/**
 * Ordered LP effort rungs (#32), cheapest first — the **runtime** counterpart of the static
 * [LpTiming] technique tiers. Where [LpTiming] / [LpEmphasis] decide *which* relaxation techniques a
 * solve may build (a config-time ceiling), these rungs decide *how hard* the per-node simplex is
 * pushed during search, and the [LpEffortLadder] descends them under cost. The two vocabularies meet
 * at the emphasis→ceiling boundary, one rung per simplex tier:
 *  - [CUTS]  — the per-node LP plus during-search cut separation; the runtime form of [LpTiming.EXHAUSTIVE].
 *  - [BOUND] — the per-node LP bound / infeasibility prune, no during-search cuts; the [LpTiming.MEDIUM] tier.
 *  - [OFF]   — no per-node LP (the bare combinatorial [LpTiming.FAST] arms, if any, are separate and
 *              not governed by this ladder).
 *
 * The [LpEmphasis] ceiling fixes the ladder's top rung via [ceiling]: AGGRESSIVE (cuts permitted) →
 * [CUTS], DEFAULT (simplex, no cuts) → [BOUND]; a lower emphasis leaves the simplex off, so no ladder.
 */
internal enum class LpEffort {
    OFF,
    BOUND,
    CUTS,
    ;

    companion object {
        /** The ladder's ceiling rung for a resolved plan: [CUTS] when during-search cuts are permitted,
         *  else the bare [BOUND]. The single source of the emphasis→top-rung mapping (#45). */
        fun ceiling(cutsPermitted: Boolean): LpEffort = if (cutsPermitted) CUTS else BOUND
    }
}

/**
 * Adaptive per-node LP effort ladder (#32/#33), generalizing the two-rung auto-off (#614) to the
 * ordered [LpEffort] rungs. The `--lp` emphasis fixes the [top] rung (a ceiling the ladder never
 * exceeds); from there a tumbling window demotes one rung at a time when a rung's prunes no longer
 * justify its cost, and a demoted ladder re-probes the next rung up on exponential backoff, recovering
 * a subtree where the extra effort becomes useful again.
 *
 * The demotion is reward-driven (#33): a rung holds only if its window prune count clears a floor
 * scaled by the rung's per-node cost ([cutCostWeight] for [LpEffort.CUTS], `1` for the bare bound).
 * So the expensive cut tier — which issues extra separation re-solves per node — is shed unless it
 * earns proportionally more prunes, while a cheaper bound that prunes occasionally is kept. This is the
 * count-based, deterministic analogue of a reward-per-cost bandit over the rungs (no wall-clock).
 *
 * Soundness: every rung is a valid bound / infeasibility check / off, so the rung only changes *how
 * much* work is done, never the search's correctness — dropping effort loses pruning, not solutions.
 * Like the auto-off it supersedes, the controller is count-based (no wall-clock), so it is deterministic
 * and unit-testable. With `top = BOUND` it reduces exactly to the old two-rung auto-off.
 *
 * @param top          the ceiling rung from the emphasis; the ladder starts here and never exceeds it.
 * @param warmup       passes before the first demotion — never demote during the warmup.
 * @param window       size of the tumbling prune-rate window evaluated after the warmup.
 * @param minWindowPrunes lowest prune count a window may have and hold the bare [LpEffort.BOUND] rung;
 *                        below it the ladder demotes. The default `1` keeps a bound that prunes rarely.
 * @param cutCostWeight cost multiplier of the [LpEffort.CUTS] rung relative to [LpEffort.BOUND] (#33):
 *                      a node at CUTS issues extra separation re-solves, so the reward-driven demote
 *                      requires the cut rung to prune `cutCostWeight ×` as often to justify staying.
 *                      `1` reverts to the cost-blind #32 rule (demote any rung at `< minWindowPrunes`).
 * @param reprobeBase  eligible nodes between the first re-probes of the next rung up.
 * @param reprobeMax   ceiling on the backoff interval — a demoted ladder still re-probes this often.
 */
internal class LpEffortLadder(
    private val top: LpEffort,
    private val warmup: Int = 64,
    private val window: Int = 64,
    private val minWindowPrunes: Int = 1,
    private val cutCostWeight: Int = DEFAULT_CUT_COST_WEIGHT,
    private val reprobeBase: Int = DEFAULT_REPROBE_BASE,
    private val reprobeMax: Int = 8192,
) {
    companion object {
        /** Default first backoff interval; `Int.MAX_VALUE` instead makes a demotion irreversible (#562). */
        const val DEFAULT_REPROBE_BASE: Int = 64

        /** Default cut-rung cost weight (#33): the per-node cut separation runs up to a few extra LP
         *  re-solves, so the cut rung must earn proportionally more prunes than the bare bound to hold. */
        const val DEFAULT_CUT_COST_WEIGHT: Int = 3
    }

    private val rungs = LpEffort.entries
    private var current = top
    private var runEffort = top
    private var totalSolves = 0

    // Tumbling-window accounting at the current rung.
    private var windowSolves = 0
    private var windowPrunes = 0

    // Re-probe schedule while below the ceiling.
    private var sinceLastProbe = 0
    private var reprobeDelay = reprobeBase
    private var probing = false

    /** The settled rung the ladder has descended/promoted to (excludes the transient re-probe rung). */
    val rung: LpEffort get() = current

    /** The rung to run for the pass [shouldRun] just authorized — the current rung, or the next rung
     *  up during a re-probe. */
    val effort: LpEffort get() = runEffort

    /** Whether during-search cut separation runs this pass — i.e. the run rung is [LpEffort.CUTS]. */
    val cutsEnabled: Boolean get() = runEffort == LpEffort.CUTS

    /**
     * Call once per LP-eligible node (after the depth / cadence gates pass). Returns true when the LP
     * should run now — always while the current rung is above [LpEffort.OFF], and on the scheduled
     * re-probe of the next rung up while below the ceiling. Sets [effort] to the rung to run. Stateful:
     * advances the re-probe clock, so call it exactly once per eligible node.
     */
    fun shouldRun(): Boolean {
        if (current.ordinal < top.ordinal) {
            sinceLastProbe++
            if (sinceLastProbe >= reprobeDelay) {
                probing = true
                runEffort = rungs[current.ordinal + 1]
                return true
            }
        }
        runEffort = current
        return current != LpEffort.OFF
    }

    /** Call after an LP run started by [shouldRun], with whether it pruned. */
    fun record(pruned: Boolean) {
        totalSolves++
        if (probing) {
            // A re-probe of the next rung up: a prune means that rung is worth its cost — promote and
            // re-arm the window; otherwise back off (doubling, capped) and hold the current rung.
            probing = false
            sinceLastProbe = 0
            if (pruned) {
                current = rungs[current.ordinal + 1]
                windowSolves = 0
                windowPrunes = 0
                reprobeDelay = reprobeBase
            } else {
                reprobeDelay = minOf(reprobeDelay * 2, reprobeMax)
            }
            return
        }
        windowSolves++
        if (pruned) windowPrunes++
        if (totalSolves >= warmup && windowSolves >= window) {
            // Reward-driven demotion (#33): a rung holds only if its window prunes clear the floor
            // scaled by the rung's cost — the bare bound at `minWindowPrunes`, the costlier cut rung at
            // `minWindowPrunes × cutCostWeight`, so cuts must earn their extra re-solves to stay.
            if (windowPrunes < minWindowPrunes * costWeight(current) && current != LpEffort.OFF) {
                current = rungs[current.ordinal - 1]
                sinceLastProbe = 0
                reprobeDelay = reprobeBase
            }
            windowSolves = 0
            windowPrunes = 0
        }
    }

    /** Per-node cost of running rung [e], relative to the bare [LpEffort.BOUND] solve (#33). */
    private fun costWeight(e: LpEffort): Int = if (e == LpEffort.CUTS) cutCostWeight else 1
}
