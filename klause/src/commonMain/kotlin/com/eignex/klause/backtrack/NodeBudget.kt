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
 * once across the solve rather than once per arm. Reaching every engine is the caller's job and the easy
 * half to get wrong: an arm that builds its own [BacktrackParams] — the hybrid-ALNS repair — is invisible
 * to anything that edits a recipe pool, and a driver that reads only [BacktrackParams.maxDecisions]
 * spends nothing. Both leave a cap that looks set and bounds nothing.
 *
 * Charged at the event the node statistic counts, so a run stops on the node that exhausts the allowance
 * and `-s` reports exactly the cap: 500, 2000 and 8000 nodes for those three caps, and one cap repeated
 * four times gave one number. That is what a comparison needs, and what a wall-clock budget cannot give —
 * the same pair of builds measured 622 against 2371 nodes on a loaded box and neutral in a lull.
 *
 * Local search has no nodes, so it charges its moves at the rate a portfolio prices them against a node
 * ([LS_INSTRUCTIONS_PER_WORK]), and stops its run at the move that would pass the allowance. A local-search run
 * under the cap is therefore as reproducible as a backtrack one.
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
    private var moves: Long = 0L

    /** Node equivalents spent against this allowance: nodes visited, and local-search moves at their price. */
    val spent: Long get() = used + (moves / LS_INSTRUCTIONS_PER_WORK).toLong()

    /** Record one visited decision node against the allowance. */
    internal fun spend() {
        used++
    }

    /** Record [count] local-search moves against the allowance. */
    internal fun spendMoves(count: Long) {
        moves += count
    }

    /** Local-search moves the allowance still covers. */
    internal fun movesLeft(): Long = ((limit - used) * LS_INSTRUCTIONS_PER_WORK).toLong().minus(moves).coerceAtLeast(0L)

    /** Whether the allowance is gone. A driver's cancellation token reads this to stop re-entering. */
    fun exhausted(): Boolean = spent >= limit
}

/**
 * Local-search instructions that cost as much as one backtrack search node, LP work included: the median ratio of
 * local-search moves per second to backtrack work per second, each engine alone on one core for 10s, over the 28
 * MiniZinc models where both ran (spread 0.17 to 69, geometric mean 1.9).
 */
internal const val LS_INSTRUCTIONS_PER_WORK: Double = 1.5
