package com.eignex.klause.solver.pipeline

internal fun TheoryParams.strategyQuotas(count: Int, complete: Boolean): List<TheoryParams> {
    val completeSlots = if (complete) setOf(0) else emptySet()
    val work = nativeWorkerAllowances(count, openWorkLimit, completeSlots)
    val leaves = nativeWorkerAllowances(count, maxLeaves, completeSlots)
    val decisions = nativeWorkerAllowances(count, maxDecisions, completeSlots)
    val flips = heuristicWorkerAllowances(count, maxFlips, completeSlots)
    val instructions = maxInstructions?.let { heuristicWorkerAllowances(count, it, completeSlots) }
    return List(count) { index -> copy(
        openWorkLimit = work[index], maxLeaves = leaves[index], maxDecisions = decisions[index],
        maxFlips = flips[index], maxInstructions = instructions?.get(index),
    ) }
}
