package com.eignex.klause.lp.bounding

/**
 * Decides when the node LP has stopped earning the search time it costs, and demotes it to a floor
 * budget rather than switching it off.
 *
 * The signal is **deterministic work per node explored** ([LpWork] operations), not wall-clock time.
 * A ratio rather than an absolute budget because it is scale-free: it says *the LP is taxing the
 * search* without needing to know how long the run may take or how fast the machine is, and it reads
 * the same on a loaded box as on an idle one. That is what lets two identical invocations report
 * identical counters, which a clock-driven rule cannot.
 *
 * Wall-clock survives as a **backstop only**, and the distinction matters:
 *  - work *shapes* the policy — how much to spend, when to demote;
 *  - the clock may only *stop* runaway work, never redistribute it.
 *
 * The backstop is here because the work meter is a proxy: it charges the entries the simplex kernels
 * touch, and cannot see allocation, garbage collection, exact `Int128` certification or the rational
 * fallback. If one of those dominates, a purely deterministic policy would still burn the deadline. Set
 * so it does not fire in ordinary operation — a backstop that never fires costs no reproducibility —
 * and [backstopFired] records when it did, so a run whose counters do not reproduce says why instead of
 * leaving it a mystery.
 *
 * A prune spares the LP permanently, on the same reasoning [LpWorkBudget] uses: a relaxation that
 * prunes is worth its cost, and the models with the most to gain from demotion are the ones that never
 * prune at all.
 */
internal class LpEffortGovernor(
    private val opsPerNodeCap: Long,
    private val wallBackstopMillis: Long,
    private val warmupSolves: Int,
) {
    private var ops = 0L
    private var nodes = 0L
    private var solves = 0
    private var spentMillis = 0L
    private var everPruned = false
    private var demoted = false

    /** Whether the node LP has been demoted to its floor budget. Never a hard off switch: a demoted LP
     *  still bounds, and with a persistent basis its solves still advance the next one. */
    val isDemoted: Boolean get() = demoted

    /** Whether the wall-clock backstop — rather than the deterministic ratio — caused the demotion.
     *  Reported so a run that does not reproduce is explained by it. */
    var backstopFired: Boolean = false
        private set

    /** Note one node the search visited, whether or not it ran an LP. */
    fun observeNode() {
        nodes++
    }

    /** Note one node LP solve: the [opsSpent] it charged and whether it [pruned]. */
    fun observeSolve(opsSpent: Long, pruned: Boolean) {
        ops += opsSpent
        solves++
        if (pruned) {
            // A demotion is a judgement about a relaxation that was not paying; a prune is that
            // judgement being wrong. Restore it rather than leaving the LP throttled on stale evidence —
            // the old wall-clock breaker latched here, and could never take the correction.
            everPruned = true
            demoted = false
        }
        if (demoted || everPruned || solves < warmupSolves || opsPerNodeCap <= 0L) return
        // Divide rather than compare against `cap × nodes`, which overflows on a large cap and wraps
        // negative — demoting every LP instead of none.
        if (nodes > 0L && ops / nodes > opsPerNodeCap) demoted = true
    }

    /**
     * Charge [millis] of LP wall time against the backstop. Only ever tightens: it demotes, and records
     * that it was the clock and not the work that decided.
     */
    fun chargeWall(millis: Long) {
        spentMillis += millis
        if (demoted || everPruned || wallBackstopMillis <= 0L) return
        if (spentMillis >= wallBackstopMillis) {
            demoted = true
            backstopFired = true
        }
    }

    /** Milliseconds of backstop left, or null when it is disabled — used to time-box the one-shot root
     *  work against the same allowance the per-node solves draw from. */
    fun remainingMillis(): Long? =
        if (wallBackstopMillis > 0L) (wallBackstopMillis - spentMillis).coerceAtLeast(0L) else null
}
