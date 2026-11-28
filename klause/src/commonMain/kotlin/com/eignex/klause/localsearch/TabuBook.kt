package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem

/**
 * Tabu and activity bookkeeping that [LocalSearchState]'s apply path advances: the accepted-move [step] clock, the
 * per-variable [lastTouched] stamps and the cross-epoch [touchCount] histogram.
 */
class TabuBook private constructor(
    problem: Problem,
    /** Step at which each variable last moved, reset with the tabu window. Bool ids come first, then int ids offset
     *  by `numBoolVars`, then real ids offset by `numBoolVars + numIntVars`. */
    val lastTouched: LongArray,
    /** Moves applied to each variable across the whole run, indexed like [lastTouched]; survives [reset]. */
    val touchCount: IntArray,
) {
    constructor(problem: Problem) : this(
        problem, LongArray(problem.numBoolVars + problem.numIntVars + problem.numRealVars),
        IntArray(problem.numBoolVars + problem.numIntVars + problem.numRealVars),
    )

    internal companion object {
        fun preparation(problem: Problem): Sequence<TabuBook?> = sequence {
            val slots = problem.numBoolVars + problem.numIntVars + problem.numRealVars
            val lastTouched = LongArray(slots)
            yield(null)
            val touchCount = IntArray(slots)
            yield(TabuBook(problem, lastTouched, touchCount))
        }
    }

    private val numBoolVars: Int = problem.numBoolVars
    private val numDiscreteVars: Int = problem.numBoolVars + problem.numIntVars

    /** Accepted-move counter, the clock [lastTouched] is stamped with. */
    var step: Long = 0L
        internal set

    internal fun reset() {
        for (i in lastTouched.indices) lastTouched[i] = 0L
        step = 0L
    }

    /** True iff [move]'s var moved within the last [tenure] accepted moves; a compound is tabu when any part is. */
    fun isTaboo(move: Move, tenure: Int): Boolean {
        if (tenure <= 0) return false
        return when (move) {
            is Move.BoolFlip -> isTabooSlot(move.varId, tenure)
            is Move.IntSet -> isTabooSlot(numBoolVars + move.varId, tenure)
            is Move.RealSet -> isTabooSlot(numDiscreteVars + move.varId, tenure)
            is Move.Compound -> move.parts.any { isTaboo(it, tenure) }
        }
    }

    private fun isTabooSlot(slot: Int, tenure: Int): Boolean {
        val touched = lastTouched[slot]
        if (touched == 0L) return false
        return step - touched < tenure
    }
}
