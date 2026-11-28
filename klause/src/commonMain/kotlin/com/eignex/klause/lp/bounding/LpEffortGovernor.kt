package com.eignex.klause.lp.bounding

// Demotes node LP to a floor budget when its work per explored node exceeds a cap, and stops optional LP
// once a cumulative work allowance is spent. Both signals count deterministic work, never a clock, so
// identical invocations demote and stop at the same points. A prune restarts the ratio's evidence window.
internal class LpEffortGovernor(
    private val opsPerNodeCap: Long,
    private val workAllowance: Long,
    private val warmupSolves: Int,
) {
    private var ops = 0L
    private var nodes = 0L
    private var solves = 0
    private var spentWork = 0L
    private var demoted = false

    val isDemoted: Boolean get() = demoted

    val allowanceSpent: Boolean get() = workAllowance > 0L && spentWork >= workAllowance

    fun observeNode() {
        nodes++
    }

    // A workStoppedAfterProgress query has not supplied a point to assess.
    fun observeSolve(opsSpent: Long, pruned: Boolean, workStoppedAfterProgress: Boolean = false) {
        ops += opsSpent
        if (pruned) {
            // A prune lifts a ratio demotion (not an allowance one) and restarts the evidence window.
            demoted = allowanceSpent
            ops = 0L
            nodes = 0L
            solves = 0
            return
        }
        if (workStoppedAfterProgress) return
        solves++
        if (demoted || solves < warmupSolves || opsPerNodeCap <= 0L) return
        // Divide rather than compare against `cap × nodes`, which overflows on a large cap and wraps
        // negative — demoting every LP instead of none.
        if (nodes > 0L && ops / nodes > opsPerNodeCap) demoted = true
    }

    fun chargeWork(work: Long) {
        val charge = work.coerceAtLeast(0L)
        spentWork += minOf(charge, Long.MAX_VALUE - spentWork)
        if (allowanceSpent) demoted = true
    }

    // Caps each LP solve, so one solve cannot run far past the allowance before the ledger sees it.
    fun remainingWork(): Long? = if (workAllowance > 0L) (workAllowance - spentWork).coerceAtLeast(0L) else null
}
