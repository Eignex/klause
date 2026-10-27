package com.eignex.klause.solver.pipeline

/** Invocation-wide observations of checked integer cell reuse, summed across private cursors. */
data class NonlinearIntegerCellReuseStats(
    /** Whether checked univariate cell production was enabled for the invocation. */
    val producerEnabled: Boolean,
    /** Whether source-cell filtering was enabled for fixed integer tuples. */
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
    /** Whether alternating checked cell-complement proposals were enabled. */
    val steeringEnabled: Boolean = false,
    /** Charged nearest-complement selections, including fallbacks. */
    val selections: Long = 0,
    /** Selections that changed the proposal to an unvisited allowed tuple. */
    val steeredTuples: Long = 0,
    /** Distinct in-window tuples reaching full fixed-integer completion. */
    val allowedTuples: Long = 0,
    /** Candidate attempts rejected as already visited. */
    val duplicateTuples: Long = 0,
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
        params.steerSourceCells, workers.sumOf { it.cellStats.selections }, workers.sumOf { it.cellStats.steered },
        workers.sumOf { it.cellStats.allowed }, workers.sumOf { it.cellStats.duplicates },
    )
}
