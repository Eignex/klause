package com.eignex.klause.backtrack

import com.eignex.klause.solver.resources.NodeAllowance

/**
 * A solve-wide decision and local-search allowance, retained across resumes, reseeds and repairs.
 *
 * Decision nodes cost one unit. Local-search instructions cost five quarters of a unit;
 * fractional charges carry across segments. Native engine statistics retain their own units.
 * Zero is an exhausted allowance; a missing budget leaves this currency unlimited.
 *
 * Built-in parallel portfolios partition the remaining allowance into persistent worker quotas.
 * Unused quota is not transferred to another worker. Completed atomic work can overshoot a quota;
 * that work remains charged and cancellation stops later operations at their polling boundaries.
 * Aggregation is synchronized, but concurrent scheduling is not reproducible.
 */
class NodeBudget private constructor(internal val allowance: NodeAllowance) {
    /** Create a solve allowance, including an already exhausted zero allowance. */
    constructor(limit: Long) : this(NodeAllowance(limit))

    /** Decision-node equivalents available to this solve or worker quota. */
    val limit: Long get() = allowance.limit

    /** Completed decision-node equivalents, including cooperative overspend. */
    val spent: Long get() = allowance.spent

    /** Whether completed work has consumed this allowance or a containing allowance. */
    fun exhausted(): Boolean = allowance.exhausted()

    internal fun spend() = allowance.spend()
    internal fun spendMoves(count: Long) = allowance.spendMoves(count)
    internal fun decisionsLeft(): Long = allowance.decisionsLeft()
    internal fun movesLeft(): Long = allowance.movesLeft()

    private var children: List<NodeBudget>? = null

    internal fun quotas(count: Int): List<NodeBudget> = allowance.mapQuotas(count) { quotas ->
        children ?: quotas.map { NodeBudget(it) }.also { children = it }
    }
}

internal const val LS_INSTRUCTIONS_PER_WORK: Double =
    com.eignex.klause.solver.resources.LS_INSTRUCTIONS_PER_WORK
