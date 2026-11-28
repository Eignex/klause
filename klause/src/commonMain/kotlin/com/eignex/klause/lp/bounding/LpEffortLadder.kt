package com.eignex.klause.lp.bounding

// Runtime per-node simplex effort, cheapest first: the counterpart of the static LpTiming tiers. CUTS adds
// during-search separation (EXHAUSTIVE), BOUND is the bare LP prune (MEDIUM), and OFF runs no node LP. The
// FAST combinatorial bounds are outside this ladder.
internal enum class LpEffort {
    OFF,
    BOUND,
    CUTS,
    ;

    companion object {
        fun ceiling(cutsPermitted: Boolean): LpEffort = if (cutsPermitted) CUTS else BOUND
    }
}

// Starts at the emphasis ceiling `top`; a tumbling window demotes one rung when its prunes fall below
// minWindowPrunes scaled by the rung's cost (cutCostWeight for CUTS), and a demoted ladder re-probes the
// next rung up on exponential backoff. Count-based, so deterministic. Every rung is sound; dropping effort
// loses pruning, not solutions.
internal class LpEffortLadder(
    private val top: LpEffort,
    private val warmup: Int = DEFAULT_WARMUP,
    private val window: Int = 64,
    private val minWindowPrunes: Int = 1,
    private val cutCostWeight: Int = DEFAULT_CUT_COST_WEIGHT,
    private val reprobeBase: Int = DEFAULT_REPROBE_BASE,
    private val reprobeMax: Int = 8192,
) {
    companion object {
        // Shared by LpEffortGovernor and LpPivotBudget; each admits observations under its own conditions.
        const val DEFAULT_WARMUP: Int = 64

        // `Int.MAX_VALUE` instead makes a demotion irreversible.
        const val DEFAULT_REPROBE_BASE: Int = 64

        // Node cut separation runs a few extra LP re-solves, so the cut rung must earn more prunes to hold.
        const val DEFAULT_CUT_COST_WEIGHT: Int = 3
    }

    private val rungs = LpEffort.entries
    private var ceiling = top
    private var current = top
    private var runEffort = top
    private var totalSolves = 0

    private var windowSolves = 0
    private var windowPrunes = 0

    private var sinceLastProbe = 0
    private var reprobeDelay = reprobeBase
    private var probing = false

    // Excludes the transient re-probe rung.
    val rung: LpEffort get() = current

    val effort: LpEffort get() = runEffort

    val cutsEnabled: Boolean get() = runEffort == LpEffort.CUTS

    // Eligibility constrains effort without renewing reward windows or the re-probe clock.
    fun setCutsSupported(supported: Boolean) {
        ceiling = if (supported) top else minOf(top, LpEffort.BOUND)
        current = minOf(current, ceiling)
        runEffort = minOf(runEffort, ceiling)
        if (probing && runEffort == current) probing = false
    }

    // Advances the re-probe clock, so call it exactly once per LP-eligible node.
    fun shouldRun(): Boolean {
        if (current.ordinal < ceiling.ordinal) {
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

    fun record(pruned: Boolean) {
        totalSolves++
        if (probing) {
            // A pruning re-probe promotes; otherwise the backoff doubles up to its cap.
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
            if (windowPrunes < minWindowPrunes * costWeight(current) && current != LpEffort.OFF) {
                current = rungs[current.ordinal - 1]
                sinceLastProbe = 0
                reprobeDelay = reprobeBase
            }
            windowSolves = 0
            windowPrunes = 0
        }
    }

    private fun costWeight(e: LpEffort): Int = if (e == LpEffort.CUTS) cutCostWeight else 1
}
