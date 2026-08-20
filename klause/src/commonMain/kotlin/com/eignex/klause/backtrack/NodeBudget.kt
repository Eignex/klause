package com.eignex.klause.backtrack

/**
 * A cap on decision nodes that spans a whole solve, for holding two builds to identical work.
 *
 * [BacktrackParams.maxDecisions] bounds one *slice*: a driver that re-enters the engine — a restart, a
 * portfolio arm resuming, an ALNS repair — starts a fresh allowance each time, so the nodes a solve
 * visits are not a function of it. Measured on one instance at a fixed deadline, a cap of 200 visited
 * 5186 nodes and a cap of 1000 visited 3597: not a bound on the solve, and not even monotone in the cap.
 * That makes it useless for comparing two builds, which is the one thing a shared machine's wall clock
 * cannot do either.
 *
 * The counter is shared by every engine reading the same [BacktrackParams], so the allowance is spent
 * once across the solve rather than once per arm.
 *
 * Charged at the event the node statistic counts, so a run stops on the node that exhausts the allowance
 * and `-s` reports exactly the cap: 500, 2000 and 8000 nodes for those three caps, and one cap repeated
 * four times gave one number. That is what a comparison needs, and what a wall-clock budget cannot give —
 * the same pair of builds measured 622 against 2371 nodes on a loaded box and neutral in a lull.
 *
 * Deliberately **not** synchronised. Under a parallel pool the arms race on the counter, so the cap stays
 * a bound but stops being reproducible; single-worker runs are what it is for.
 */
class NodeBudget(
    /** Decision nodes the solve may visit — the same figure `-s` reports as `nodes`. */
    val limit: Long,
) {
    init {
        require(limit > 0) { "node budget must be positive, got $limit" }
    }

    private var used: Long = 0L

    /** Nodes visited against this allowance. */
    val spent: Long get() = used

    /** Record one visited decision node against the allowance. */
    internal fun spend() {
        used++
    }

    /** Whether the allowance is gone. A driver's cancellation token reads this to stop re-entering. */
    fun exhausted(): Boolean = used >= limit
}
