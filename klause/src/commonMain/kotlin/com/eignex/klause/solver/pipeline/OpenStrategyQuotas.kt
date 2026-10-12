package com.eignex.klause.solver.pipeline

internal fun TheoryParams.strategyQuotas(count: Int, complete: Boolean): List<TheoryParams> {
    require(count > 0)
    fun shares(limit: Long): LongArray {
        if (limit == Long.MAX_VALUE) return LongArray(count) { Long.MAX_VALUE }
        if (count == 1) return longArrayOf(limit)
        val first = if (complete) limit / 4 * 3 + limit % 4 * 3 / 4 else 0L
        val others = if (complete) count - 1 else count
        val remaining = limit - first
        return LongArray(count) { index ->
            if (complete && index == 0) first else {
                val position = if (complete) index - 1 else index
                remaining / others + if (position < remaining % others) 1L else 0L
            }
        }
    }
    val work = shares(openWorkLimit)
    val leaves = shares(maxLeaves)
    val decisions = shares(maxDecisions)
    val heuristicCount = count - if (complete) 1 else 0
    fun heuristicShare(limit: Long, index: Int): Long {
        if (complete && index == 0) return 0
        if (limit == Long.MAX_VALUE) return limit
        val position = index - if (complete) 1 else 0
        return limit / heuristicCount + if (position < limit % heuristicCount) 1L else 0L
    }
    return List(count) { index -> copy(
        openWorkLimit = work[index], maxLeaves = leaves[index], maxDecisions = decisions[index],
        maxFlips = heuristicShare(maxFlips, index),
        maxInstructions = maxInstructions?.let { heuristicShare(it, index) },
    ) }
}
