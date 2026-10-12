package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.core.Concurrency

internal class PortfolioWorkerSlot(
    val index: Int,
    val seed: Long,
    val cancellation: Cancellation,
    val nodeBudget: NodeBudget?,
)

internal class PortfolioInvocationResources(
    count: Int,
    parallelism: Int,
    seed: Long,
    cancellation: Cancellation,
    nodeBudget: NodeBudget?,
) {
    init { require(count > 0 && parallelism > 0) }
    val lanes: Int = minOf(count, parallelism)
    val concurrency: Concurrency = if (lanes > 1) Concurrency.Strict else Concurrency.None
    private val quotas = if (lanes > 1) nodeBudget?.quotas(count) else null
    private val workerSlots: List<PortfolioWorkerSlot> = List(count) { index ->
        PortfolioWorkerSlot(index, seed + index, cancellation, quotas?.get(index) ?: nodeBudget)
    }

    val slots: List<PortfolioWorkerSlot> get() = workerSlots.toList()

    fun nativeAllowances(limit: Long, complete: Set<Int>): LongArray =
        nativeWorkerAllowances(workerSlots.size, limit, complete)
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
