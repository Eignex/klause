package com.eignex.klause.solver.result

/**
 * What the unverified Boolean hints of one open-model solve produced, and what drawing them cost.
 *
 * A hint only reorders branches, so none of this bears on the verdict. It is the accounting a
 * measurement needs: a run whose producer steered the traversal and a run whose producer found nothing
 * to propose are otherwise indistinguishable from the verdict alone, and a draw that proposed nothing
 * still spent its allowance.
 */
data class OpenHintStats(
    /** Hint draws attempted, at most one per request. */
    val draws: Long = 0,
    /** Draws that produced a hint the traversal was steered by. */
    val applied: Long = 0,
    /** Source Boolean columns the produced hints cover. */
    val hintedVars: Long = 0,
    /** Local-search moves the draws spent. */
    val moves: Long = 0,
) {
    /** Combine counters from independent solve slices. */
    fun mergedWith(other: OpenHintStats): OpenHintStats = OpenHintStats(
        draws + other.draws,
        applied + other.applied,
        hintedVars + other.hintedVars,
        moves + other.moves,
    )
}
