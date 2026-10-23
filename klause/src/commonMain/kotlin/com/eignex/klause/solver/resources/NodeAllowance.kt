package com.eignex.klause.solver.resources

import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock

internal class NodeAllowance private constructor(
    val limit: Long,
    private val ledger: Ledger,
    private val parent: NodeAllowance?,
    private val tracksParentRemaining: Boolean,
) {
    constructor(limit: Long) : this(limit, Ledger(), null, false)

    init {
        require(limit >= 0L) { "node budget must be nonnegative, got $limit" }
    }

    private var used = 0L
    private var moves = 0L
    private var children: List<NodeAllowance>? = null

    val spent: Long get() = ledger.lock.withLock { spentUnlocked() }

    internal fun spend() = ledger.lock.withLock {
        record(nodes = 1L, instructions = 0L)
    }

    internal fun spendMoves(count: Long) = ledger.lock.withLock {
        if (count > 0L) record(nodes = 0L, instructions = count)
    }

    internal fun decisionsLeft(): Long = ledger.lock.withLock { remainingUnlocked() }

    internal fun movesLeft(): Long = ledger.lock.withLock { movesLeftUnlocked() }

    fun exhausted(): Boolean = ledger.lock.withLock { remainingUnlocked() == 0L }

    internal fun boundedPhase(maximum: Long): NodeAllowance = ledger.lock.withLock {
        require(maximum >= 0L)
        NodeAllowance(minOf(maximum, remainingUnlocked()), ledger, this, true)
    }

    internal fun quotas(count: Int): List<NodeAllowance> = mapQuotas(count) { it }

    internal fun <T> mapQuotas(count: Int, map: (List<NodeAllowance>) -> T): T = ledger.lock.withLock {
        require(count > 0)
        children?.let {
            require(it.size == count) { "a solve allowance cannot be repartitioned for another worker count" }
            return@withLock map(it)
        }
        val remaining = remainingUnlocked()
        val share = remaining / count
        val remainder = remaining % count
        List(count) { index -> NodeAllowance(share + if (index < remainder) 1L else 0L, ledger, this, false) }
            .also { children = it }.let(map)
    }

    private fun spentUnlocked(): Long = saturatedSum(used, pricedInstructions(moves))

    private fun remainingUnlocked(): Long = minOf(
        (limit - spentUnlocked()).coerceAtLeast(0L),
        if (tracksParentRemaining) parent?.remainingUnlocked() ?: Long.MAX_VALUE else Long.MAX_VALUE,
    )

    private fun movesLeftUnlocked(): Long = minOf(
        (instructionsFor((limit - used).coerceAtLeast(0L)) - moves).coerceAtLeast(0L),
        if (tracksParentRemaining) parent?.movesLeftUnlocked() ?: Long.MAX_VALUE else Long.MAX_VALUE,
    )

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

internal const val LS_INSTRUCTIONS_PER_WORK: Double = 0.8
