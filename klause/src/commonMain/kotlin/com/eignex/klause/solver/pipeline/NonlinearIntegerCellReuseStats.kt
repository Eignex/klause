package com.eignex.klause.solver.pipeline

/** Invocation-wide observations of checked integer cell reuse, summed across private cursors. */
data class NonlinearIntegerCellReuseStats(
    /** Whether polynomial completions produced checked univariate cells. */
    val producerEnabled: Boolean,
    /** Whether unvisited integer tuples were tested against retained source cells. */
    val reuseEnabled: Boolean,
    /** Completed typed cells delivered to integer cursors. */
    val observed: Long,
    /** Source cells admitted to bounded cursor-owned stores. */
    val retained: Long,
    /** Cells refused by ownership, integer identity, capacity or endpoint representation. */
    val declined: Long,
    /** Unvisited in-window integer tuples excluded before requesting a completion. */
    val excludedTuples: Long,
    /** Excluded tuples using a retained cell that already excluded an earlier unvisited tuple. */
    val repeatedSupports: Long,
    /** Polynomial checks requested, including relaxation and real repair queries. */
    val completionChecks: Long,
)

internal fun cellReuseStats(
    params: NonlinearIntegerTheoryParams,
    workers: List<NonlinearIntegerInvocation>,
): NonlinearIntegerCellReuseStats? {
    if (!params.cellRefinement) return null
    return NonlinearIntegerCellReuseStats(
        true, params.reuseSourceCells,
        workers.sumOf { it.cellStats.observed }, workers.sumOf { it.cellStats.retained },
        workers.sumOf { it.cellStats.declined }, workers.sumOf { it.cellStats.excluded },
        workers.sumOf { it.cellStats.repeated }, workers.sumOf { it.completionChecks },
    )
}
