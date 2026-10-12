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

    fun nativeAllowances(limit: Long, complete: Set<Int>): LongArray {
        require(limit >= 0 && complete.all { it in workerSlots.indices })
        if (limit == Long.MAX_VALUE) return LongArray(workerSlots.size) { Long.MAX_VALUE }
        val result = LongArray(workerSlots.size)
        val completeSlots = workerSlots.indices.filter { it in complete }
        val heuristicSlots = workerSlots.indices.filter { it !in complete }
        val completeAllowance = when {
            completeSlots.isEmpty() -> 0L
            heuristicSlots.isEmpty() -> limit
            else -> limit / 4 * 3 + limit % 4 * 3 / 4
        }
        fun allocate(amount: Long, indexes: List<Int>) {
            for ((position, index) in indexes.withIndex()) {
                result[index] = amount / indexes.size + if (position < amount % indexes.size) 1L else 0L
            }
        }
        allocate(completeAllowance, completeSlots)
        allocate(limit - completeAllowance, heuristicSlots)
        return result
    }
}
