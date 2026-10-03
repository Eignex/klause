package com.eignex.klause.lp.bounding

/**
 * Demotes node LP work to a floor budget when its deterministic cost is too high, and stops optional LP
 * work once a cumulative work allowance is spent.
 *
 * Both signals are **deterministic work** — [com.eignex.klause.lp.engine.LpWork] operations plus the
 * node-LP overhead the engine charges in the same unit, not wall-clock time:
 *  - the **ratio** of work per node explored *shapes* the policy. It is scale-free: it says *the LP is
 *    taxing the search* without needing to know how long the run may take or how fast the machine is.
 *    A prune restarts its evidence window, so a later expensive relaxation can be demoted again.
 *  - the **allowance** caps the total. It is cumulative across root and node work, including useful
 *    solves: the LP is optional search work and cannot consume the solve's whole budget. Once spent, the
 *    node LP is skipped and the search runs as a bare combinatorial one.
 *
 * Neither reads a clock, so two identical invocations demote and stop at the same points on a loaded box
 * as on an idle one. [allowanceSpent] records when the allowance ran out.
 */
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

    /** Whether the node LP has been demoted to its floor budget. An LP whose allowance is spent is skipped. */
    val isDemoted: Boolean get() = demoted

    /** Whether the cumulative work allowance is spent. */
    val allowanceSpent: Boolean get() = workAllowance > 0L && spentWork >= workAllowance

    /** Note one node the search visited, whether or not it ran an LP. */
    fun observeNode() {
        nodes++
    }

    /** Note one node LP solve: the [opsSpent] it charged and whether it [pruned]. */
    fun observeSolve(opsSpent: Long, pruned: Boolean) {
        ops += opsSpent
        solves++
        if (pruned) {
            // A prune invalidates a ratio demotion, but starts a new evidence window so a later
            // expensive relaxation can be demoted again.
            demoted = allowanceSpent
            ops = 0L
            nodes = 0L
            solves = 0
            return
        }
        if (demoted || solves < warmupSolves || opsPerNodeCap <= 0L) return
        // Divide rather than compare against `cap × nodes`, which overflows on a large cap and wraps
        // negative — demoting every LP instead of none.
        if (nodes > 0L && ops / nodes > opsPerNodeCap) demoted = true
    }

    /** Charge [work] LP operations — root or node, solve or overhead — against the allowance. Only ever
     *  tightens: spending the allowance demotes. */
    fun chargeWork(work: Long) {
        val charge = work.coerceAtLeast(0L)
        spentWork += minOf(charge, Long.MAX_VALUE - spentWork)
        if (allowanceSpent) demoted = true
    }

    /** Work left in the allowance, or null when there is none. Caps each LP solve, so one solve cannot
     *  run far past the allowance before the ledger sees it. */
    fun remainingWork(): Long? = if (workAllowance > 0L) (workAllowance - spentWork).coerceAtLeast(0L) else null
}
