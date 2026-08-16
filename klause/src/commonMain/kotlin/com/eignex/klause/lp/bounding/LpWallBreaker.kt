package com.eignex.klause.lp.bounding

/**
 * Wall-clock circuit breaker for an unproductive per-node LP, the backstop for exactly the case
 * the count-based [LpEffortLadder] cannot reach: an LP whose single solve costs seconds never
 * accumulates the ladder's warmup window of solves, so the ladder never demotes it and it burns the
 * whole time budget bounding nothing. This breaker trips it on the wall-clock axis instead — the LP may
 * spend at most [budgetMillis] of the solve budget, and once that is exceeded it [isTripped] and the
 * caller disables per-node LP for the rest of the search.
 *
 * Two guards keep it from firing on a *productive* or *cheap* LP that the ladder is already handling:
 *  - a node prune lifts the budget's grip permanently;
 *  - once the LP has run [warmupSolves] solves the ladder has had its window to judge the LP by its
 *    prune rate, so the breaker steps aside and trusts it. A cheap LP
 *    (many fast solves, e.g. one whose value is search guidance rather than prunes) reaches the warmup
 *    well inside the budget and is left to the ladder; only an expensive LP hits the budget while still
 *    starved of solves, and that is the one worth cutting.
 *
 * Kept off [LpEngine] as a small value so the trip logic is deterministic and unit-testable (the
 * wall-clock measurement lives at the call site); `budgetMillis <= 0` disables it, so a solve with no
 * known time budget never trips.
 */
internal class LpWallBreaker(private val budgetMillis: Long, private val warmupSolves: Int) {
    private var spentMillis = 0L
    private var solves = 0
    private var everPruned = false
    private var tripped = false

    /** Whether per-node LP has been disabled: the budget was spent, before any prune, while the LP was
     *  still starved of the solves the ladder needs to judge it. Latches. */
    val isTripped: Boolean get() = tripped

    /** Charge [millis] of LP wall time for one solve, noting whether it [pruned] a node, and (re)evaluate
     *  the trip. Once tripped it stays tripped; a prune, or reaching the ladder's warmup, spares it. */
    fun charge(millis: Long, pruned: Boolean) {
        spentMillis += millis
        solves++
        if (pruned) everPruned = true
        if (!tripped && budgetMillis > 0L && !everPruned && solves < warmupSolves && spentMillis >= budgetMillis) {
            tripped = true
        }
    }

    /** Milliseconds of wall budget left, or `null` when the breaker is disabled (`budgetMillis <= 0`) —
     *  used to time-box the one-shot root work to the same budget the per-node solves draw from. */
    fun remainingMillis(): Long? = if (budgetMillis > 0L) (budgetMillis - spentMillis).coerceAtLeast(0L) else null
}
