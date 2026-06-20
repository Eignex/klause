package com.eignex.klause.solver.backtrack.lp

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
 * Adaptive per-node LP effort ladder (#32), generalizing the two-rung auto-off (#614) to the ordered
 * [LpEffort] rungs. The `--lp` emphasis fixes the [top] rung (a ceiling the ladder never exceeds);
 * from there a tumbling prune-rate window demotes one rung at a time when the LP stops paying — the
 * most expensive tier ([LpEffort.CUTS]) is shed first, the bare bound last — and a disabled-or-demoted
 * ladder re-probes the next rung up on exponential backoff, recovering a subtree where the extra effort
 * becomes useful again.
 *
 * Soundness: every rung is a valid bound / infeasibility check / off, so the rung only changes *how
 * much* work is done, never the search's correctness — dropping effort loses pruning, not solutions.
 * Like the auto-off it supersedes, the controller is count-based (no wall-clock), so it is deterministic
 * and unit-testable. With `top = BOUND` it reduces exactly to the old two-rung auto-off.
 *
 * @param top          the ceiling rung from the emphasis; the ladder starts here and never exceeds it.
 * @param warmup       passes before the first demotion — never demote during the warmup.
 * @param window       size of the tumbling prune-rate window evaluated after the warmup.
 * @param minWindowPrunes lowest prune count a window may have and hold the current rung; below it the
 *                        ladder demotes one rung. The default `1` keeps a rung that prunes even rarely.
 * @param reprobeBase  eligible nodes between the first re-probes of the next rung up.
 * @param reprobeMax   ceiling on the backoff interval — a demoted ladder still re-probes this often.
 */
internal class LpEffortLadder(
    private val top: LpEffort,
    private val warmup: Int = 64,
    private val window: Int = 64,
    private val minWindowPrunes: Int = 1,
    private val reprobeBase: Int = DEFAULT_REPROBE_BASE,
    private val reprobeMax: Int = 8192,
) {
    companion object {
        /** Default first backoff interval; `Int.MAX_VALUE` instead makes a demotion irreversible (#562). */
        const val DEFAULT_REPROBE_BASE: Int = 64
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
            if (windowPrunes < minWindowPrunes && current != LpEffort.OFF) {
                current = rungs[current.ordinal - 1]
                sinceLastProbe = 0
                reprobeDelay = reprobeBase
            }
            windowSolves = 0
            windowPrunes = 0
        }
    }
}
