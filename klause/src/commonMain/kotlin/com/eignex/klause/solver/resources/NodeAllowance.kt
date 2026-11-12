package com.eignex.klause.solver.resources

import com.eignex.kumulant.core.Concurrency
import com.eignex.util.concurrent.Mutex

internal class NodeAllowance private constructor(
    val limit: Long,
    private val ledger: Ledger,
    private val parent: NodeAllowance?,
) {
    constructor(limit: Long) : this(limit, Ledger(), null)

    init {
        require(limit >= 0L) { "node budget must be nonnegative, got $limit" }
    }

    private val used = WorkCounter(Concurrency.Relaxed)
    private val moves = WorkCounter(Concurrency.Relaxed)
    private var children: List<NodeAllowance>? = null

    val spent: Long get() = saturatedSum(used.value, pricedInstructions(moves.value))

    internal fun spend() {
        record(nodes = 1L, instructions = 0L)
    }

    internal fun spendMoves(count: Long) {
        if (count > 0L) record(nodes = 0L, instructions = count)
    }

    internal fun decisionsLeft(): Long {
        val remaining = (limit - spent).coerceAtLeast(0L)
        return parent?.let { minOf(remaining, it.decisionsLeft()) } ?: remaining
    }

    internal fun movesLeft(): Long {
        val capacity = instructionsFor((limit - used.value).coerceAtLeast(0L))
        val remaining = (capacity - moves.value).coerceAtLeast(0L)
        return parent?.let { minOf(remaining, it.movesLeft()) } ?: remaining
    }

    fun exhausted(): Boolean = decisionsLeft() == 0L

    internal fun quotas(count: Int): List<NodeAllowance> = mapQuotas(count) { it }

    internal fun <T> mapQuotas(count: Int, map: (List<NodeAllowance>) -> T): T {
        require(count > 0)
        val remaining = decisionsLeft()
        return ledger.lock.withLock {
            val quotas = children?.also {
                require(it.size == count) { "a solve allowance cannot be repartitioned for another worker count" }
            } ?: run {
                val share = remaining / count
                val remainder = remaining % count
                List(count) { index -> NodeAllowance(share + if (index < remainder) 1L else 0L, ledger, this) }
                    .also { children = it }
            }
            map(quotas)
        }
    }

    private fun record(nodes: Long, instructions: Long) {
        if (nodes > 0L) used.add(nodes)
        if (instructions > 0L) moves.add(instructions)
        parent?.record(nodes, instructions)
    }

    private class Ledger {
        val lock = Mutex()
    }
}

private fun pricedInstructions(instructions: Long): Long =
    saturatedSum(saturatedProduct(instructions / 4L, 5L), instructions % 4L * 5L / 4L)

private fun instructionsFor(work: Long): Long = work / 5L * 4L + (work % 5L * 4L + 3L) / 5L

private fun saturatedProduct(value: Long, weight: Long): Long =
    if (value > Long.MAX_VALUE / weight) Long.MAX_VALUE else value * weight

private fun saturatedSum(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

internal const val LS_INSTRUCTIONS_PER_WORK: Double = 0.8
