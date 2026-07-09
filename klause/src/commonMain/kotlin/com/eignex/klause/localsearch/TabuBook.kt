package com.eignex.klause.localsearch

import com.eignex.klause.solver.Problem

/**
 * Tabu / activity bookkeeping for an ongoing solve: the accepted-move [step] clock, the per-variable
 * last-touched stamps ([lastTouched]) driving the tabu window, and the cross-epoch [touchCount]
 * activity histogram. [LocalSearchState]'s apply path advances all three; strategies read them for
 * tabu / CCA-window decisions.
 */
class TabuBook(problem: Problem) {

    private val numBoolVars: Int = problem.numBoolVars

    /** Step counter incremented on every accepted move. Together with [lastTouched] it enforces a
     *  tabu list. */
    var step: Long = 0L
        internal set

    /** Step at which each variable was last flipped or set. Bool var ids in `[0, numBoolVars)`; int
     *  var ids offset by `numBoolVars`. Reset to zero on [reset] — used only for tabu / CCA-window
     *  decisions within a single restart epoch. For cross-epoch activity, see [touchCount]. */
    val lastTouched: LongArray = LongArray(problem.numBoolVars + problem.numIntVars)

    /** Cumulative count of moves applied to each variable, same indexing as [lastTouched]. Survives
     *  [reset] so it measures activity across the whole search run. Captured by
     *  [com.eignex.klause.localsearch.WarmState] for ALNS's `activityBiased` destroy operator. */
    val touchCount: IntArray = IntArray(problem.numBoolVars + problem.numIntVars)

    /** Clear the tabu window: zero [lastTouched] and [step]. [touchCount] is deliberately preserved
     *  so cross-epoch activity survives a restart. */
    internal fun reset() {
        for (i in lastTouched.indices) lastTouched[i] = 0L
        step = 0L
    }

    /** True iff [move]'s var was touched within the last [tenure] accepted moves. For a
     *  [Move.Compound], conservative: true if *any* part is tabu. */
    fun isTaboo(move: Move, tenure: Int): Boolean {
        if (tenure <= 0) return false
        return when (move) {
            is Move.BoolFlip -> isTabooSlot(move.varId, tenure)
            is Move.IntSet -> isTabooSlot(numBoolVars + move.varId, tenure)
            is Move.Compound -> move.parts.any { isTaboo(it, tenure) }
        }
    }

    private fun isTabooSlot(slot: Int, tenure: Int): Boolean {
        val touched = lastTouched[slot]
        if (touched == 0L) return false
        return step - touched < tenure
    }
}
