package com.eignex.klause.solver.result

/**
 * What the unverified Boolean hints of one open-model solve produced, cost, and steered.
 *
 * A hint only reorders branches, so none of this bears on the verdict. It is the accounting a
 * measurement needs, and producing a proposal is not the same as using one: propagation may fix every
 * hinted column before a split reaches it, leaving a drawn hint with no effect on the traversal at all.
 * [produced] and [steeredSplits] are what separate those cases, and a draw that proposed nothing still
 * spent its allowance.
 */
data class OpenHintStats(
    /** Hint draws attempted, at most one per request. */
    val draws: Long = 0,
    /** Draws that reached a proposal. */
    val produced: Long = 0,
    /** Source Boolean columns the proposals cover. */
    val hintedVars: Long = 0,
    /**
     * Splits whose first branch a hint selected.
     *
     * Counts the split orders the hint decided, including one it decided the same way the default order
     * would have: the traversal still ran the polarity the hint named. Zero says the hint reached no
     * split, whatever it proposed.
     */
    val steeredSplits: Long = 0,
    /** Local-search moves the draws spent. */
    val moves: Long = 0,
) {
    /** Combine counters from independent solve slices. */
    fun mergedWith(other: OpenHintStats): OpenHintStats = OpenHintStats(
        draws = draws + other.draws,
        produced = produced + other.produced,
        hintedVars = hintedVars + other.hintedVars,
        steeredSplits = steeredSplits + other.steeredSplits,
        moves = moves + other.moves,
    )
}
