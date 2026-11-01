package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.util.Cancellation

internal class PortfolioWorkerSlot(
    val index: Int,
    val baseSeed: Long,
    val cancellation: Cancellation,
    val nodeBudget: NodeBudget?,
    private val count: Int,
    completeSlots: Set<Int>,
) : AutoCloseable {
    private val complete = completeSlots.toSet()
    val seed: Long get() = baseSeed + index
    private var closed = false
    private val owned = ArrayList<AutoCloseable>()

    fun nativeAllowance(limit: Long): Long = nativeWorkerAllowances(count, limit, complete)[index]
    fun heuristicAllowance(limit: Long): Long = heuristicWorkerAllowances(count, limit, complete)[index]

    fun <T : AutoCloseable> own(resource: T): T {
        check(!closed) { "portfolio worker slot is closed" }
        owned.add(resource)
        return resource
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (resource in owned.asReversed()) {
            runCatching { resource.close() }.exceptionOrNull()?.let { error ->
                if (failure == null) failure = error else requireNotNull(failure).addSuppressed(error)
            }
        }
        owned.clear()
        failure?.let { throw it }
    }
}

internal class PortfolioInvocationResources(
    count: Int,
    parallelism: Int,
    seed: Long,
    cancellation: Cancellation,
    nodeBudget: NodeBudget?,
    completeSlots: Set<Int> = emptySet(),
) : AutoCloseable {
    init { require(count > 0 && parallelism > 0) }
    val lanes: Int = minOf(count, parallelism)
    private val quotas = if (lanes > 1) nodeBudget?.quotas(count) else null
    private val workerSlots: List<PortfolioWorkerSlot> = List(count) { index ->
        PortfolioWorkerSlot(index, seed, cancellation, quotas?.get(index) ?: nodeBudget, count, completeSlots)
    }

    val slots: List<PortfolioWorkerSlot> get() = workerSlots.toList()

    fun nativeAllowances(limit: Long, complete: Set<Int>): LongArray =
        nativeWorkerAllowances(workerSlots.size, limit, complete)

    override fun close() {
        var failure: Throwable? = null
        for (slot in workerSlots.asReversed()) {
            runCatching { slot.close() }.exceptionOrNull()?.let { error ->
                if (failure == null) failure = error else requireNotNull(failure).addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

internal fun nativeWorkerAllowances(count: Int, limit: Long, complete: Set<Int>): LongArray {
    require(count > 0 && limit >= 0 && complete.all { it in 0 until count })
    if (limit == Long.MAX_VALUE) return LongArray(count) { Long.MAX_VALUE }
    val result = LongArray(count)
    val completeSlots = (0 until count).filter { it in complete }
    val heuristicSlots = (0 until count).filter { it !in complete }
    val completeAllowance = when {
        completeSlots.isEmpty() -> 0L
        heuristicSlots.isEmpty() -> limit
        else -> limit / 4 * 3 + limit % 4 * 3 / 4
    }
    allocateNativeAllowance(completeAllowance, completeSlots, result)
    allocateNativeAllowance(limit - completeAllowance, heuristicSlots, result)
    return result
}

internal fun heuristicWorkerAllowances(count: Int, limit: Long, complete: Set<Int>): LongArray {
    require(count > 0 && limit >= 0 && complete.all { it in 0 until count })
    return LongArray(count).also {
        allocateNativeAllowance(limit, (0 until count).filter { it !in complete }, it)
    }
}

private fun allocateNativeAllowance(amount: Long, indexes: List<Int>, result: LongArray) {
    for ((position, index) in indexes.withIndex()) {
        result[index] = if (amount == Long.MAX_VALUE) Long.MAX_VALUE else
            amount / indexes.size + if (position < amount % indexes.size) 1L else 0L
    }
}
