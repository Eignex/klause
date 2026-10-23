package com.eignex.klause.localsearch

import com.eignex.klause.factor.global.AllDifferentInvariant
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.util.Cancellation

internal class BoardSwapCoverage {
    private var board = 0
    private var left = 0
    private var right = 1
    private var started = false
    private var assumptions: Assumptions? = null
    private var savedBoard = 0
    private var savedLeft = 0
    private var savedRight = 1
    private var savedStarted = false
    private var complete = false
    private var savedComplete = false

    var requested: Boolean = false
        private set

    var consulted: Boolean = false
        private set

    val pending: Boolean get() = started && !complete

    fun invalidate() {
        requested = false
        board = 0
        left = 0
        right = 1
        started = false
        complete = false
    }

    fun request(state: LocalSearchState): Boolean {
        if (state.seeding.electedImplicit.none { state.factors[it] is AllDifferentInvariant }) return false
        syncAssumptions(state)
        requested = true
        return true
    }

    fun beginPick(state: LocalSearchState) {
        consulted = false
        syncAssumptions(state)
        savedBoard = board
        savedLeft = left
        savedRight = right
        savedStarted = started
        savedComplete = complete
    }

    private fun syncAssumptions(state: LocalSearchState) {
        if (assumptions === state.assumptions) return
        invalidate()
        assumptions = state.assumptions
    }

    fun finishPick(cancelled: Boolean) {
        if (!cancelled) return
        board = savedBoard
        left = savedLeft
        right = savedRight
        started = savedStarted
        complete = savedComplete
    }

    fun generate(state: LocalSearchState, sink: MoveSink, cancellation: Cancellation): Boolean {
        val elected = state.seeding.electedImplicit
        if (elected.none { state.factors[it] is AllDifferentInvariant }) return false
        syncAssumptions(state)
        consulted = true
        started = true
        var visits = 0
        val before = sink.size
        val cap = AllDifferentInvariant.MAX_STRUCTURED_SWAPS
        try {
            while (board < elected.size && visits < cap * AllDifferentInvariant.SWAP_ATTEMPT_STRIDE &&
                sink.size - before < cap
            ) {
                if (cancellation()) return true
                visits++
                val fid = elected[board]
                val invariant = state.factors[fid] as? AllDifferentInvariant
                if (invariant == null || right >= invariant.swapCoordinateCount || state.violated.contains(fid)) {
                    board++
                    left = 0
                    right = 1
                    continue
                }
                sink.proposer = fid
                if (!invariant.allowsSwapCoordinate(state, left, sink)) {
                    left++
                    right = left + 1
                    continue
                }
                invariant.proposeSwap(state, left, right, sink)
                right++
                if (right >= invariant.swapCoordinateCount) {
                    left++
                    right = left + 1
                    if (right >= invariant.swapCoordinateCount) {
                        board++
                        left = 0
                        right = 1
                    }
                }
            }
            complete = board == elected.size
            return true
        } finally {
            sink.proposer = MoveSink.NO_PROPOSER
        }
    }
}
