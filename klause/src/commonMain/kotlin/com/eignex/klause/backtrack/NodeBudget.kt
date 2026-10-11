package com.eignex.klause.backtrack

import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock

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
class NodeBudget private constructor(
    /** Decision-node equivalents available to this solve or worker quota. */
    val limit: Long,
    private val ledger: Ledger,
    private val parent: NodeBudget?,
) {
    /** Create a solve allowance, including an already exhausted zero allowance. */
    constructor(limit: Long) : this(limit, Ledger(), null)

    init {
        require(limit >= 0L) { "node budget must be nonnegative, got $limit" }
    }

    private var used = 0L
    private var moves = 0L
    private var children: List<NodeBudget>? = null

    /** Completed decision-node equivalents, including cooperative overspend. */
    val spent: Long get() = ledger.lock.withLock { spentUnlocked() }

    internal fun spend() = ledger.lock.withLock {
        record(nodes = 1L, instructions = 0L)
    }

    internal fun spendMoves(count: Long) = ledger.lock.withLock {
        if (count > 0L) record(nodes = 0L, instructions = count)
    }

    internal fun decisionsLeft(): Long = ledger.lock.withLock { (limit - spentUnlocked()).coerceAtLeast(0L) }

    internal fun movesLeft(): Long = ledger.lock.withLock {
        val capacity = instructionsFor((limit - used).coerceAtLeast(0L))
        (capacity - moves).coerceAtLeast(0L)
    }

    /** Whether completed work has consumed this allowance. */
    fun exhausted(): Boolean = ledger.lock.withLock { spentUnlocked() >= limit }

    internal fun quotas(count: Int): List<NodeBudget> = ledger.lock.withLock {
        require(count > 0)
        children?.let {
            require(it.size == count) { "a solve allowance cannot be repartitioned for another worker count" }
            return@withLock it
        }
        val remaining = (limit - spentUnlocked()).coerceAtLeast(0L)
        val share = remaining / count
        val remainder = remaining % count
        List(count) { index -> NodeBudget(share + if (index < remainder) 1L else 0L, ledger, this) }
            .also { children = it }
    }

    private fun spentUnlocked(): Long = saturatedSum(used, pricedInstructions(moves))

    private fun record(nodes: Long, instructions: Long) {
        used = saturatedSum(used, nodes)
        moves = saturatedSum(moves, instructions)
        parent?.record(nodes, instructions)
    }

    private class Ledger {
        val lock = Concurrency.Strict.lock()
    }
}

private fun pricedInstructions(instructions: Long): Long =
    saturatedSum(saturatedProduct(instructions / 4L, 5L), instructions % 4L * 5L / 4L)

private fun instructionsFor(work: Long): Long = work / 5L * 4L + work % 5L * 4L / 5L

private fun saturatedProduct(value: Long, weight: Long): Long =
    if (value > Long.MAX_VALUE / weight) Long.MAX_VALUE else value * weight

private fun saturatedSum(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

// Portfolio policy converts native local-search instructions at four instructions per five work units.
internal const val LS_INSTRUCTIONS_PER_WORK: Double = 0.8
