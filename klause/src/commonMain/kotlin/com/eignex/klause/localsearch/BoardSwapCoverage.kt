package com.eignex.klause.localsearch

import com.eignex.klause.factor.global.AllDifferentInvariant
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

internal class BoardSwapCoverage {
    private var assumptions: Assumptions? = null
    private var cursor: Cursor? = null
    private var checked = false

    var requested: Boolean = false
        private set

    var consulted: Boolean = false
        private set

    val pending: Boolean get() = requested && cursor?.hasRemaining == true

    fun invalidate() {
        requested = false
        cursor?.invalidate()
    }

    fun request(state: LocalSearchState): Boolean {
        syncAssumptions(state)
        if (!checked) {
            val boards = state.seeding.electedImplicit.filter { state.factors[it] is AllDifferentInvariant }
            if (boards.isNotEmpty()) cursor = Cursor(boards.toIntArray())
            checked = true
        }
        val live = cursor ?: return false
        if (!requested) live.start()
        requested = true
        return true
    }

    fun beginPick(state: LocalSearchState) {
        consulted = false
        if (!requested) return
        syncAssumptions(state)
        if (requested) cursor?.beginPick()
    }

    private fun syncAssumptions(state: LocalSearchState) {
        if (assumptions === state.assumptions) return
        invalidate()
        assumptions = state.assumptions
    }

    fun finishPick(cancelled: Boolean) {
        cursor?.finishPick(cancelled && consulted)
    }

    fun generate(state: LocalSearchState, factorId: Int, sink: MoveSink, cancellation: Cancellation): Boolean {
        if (!request(state)) return false
        val live = checkNotNull(cursor)
        val board = live.boardIndex(factorId)
        if (board < 0) return false
        consulted = true
        val before = sink.size
        var visits = 0
        val cap = AllDifferentInvariant.MAX_STRUCTURED_SWAPS
        try {
            while (live.hasRemaining(board) && visits < cap * AllDifferentInvariant.SWAP_ATTEMPT_STRIDE &&
                sink.size - before < cap
            ) {
                if (cancellation()) return true
                visits++
                live.visit(state, board, sink)
            }
            return true
        } finally {
            sink.proposer = MoveSink.NO_PROPOSER
        }
    }

    private class Cursor(private val boards: IntArray) {
        private val initialized = LongArray(boards.size)
        private val finished = LongArray(boards.size)
        private val position = LongArray(boards.size)
        private val stride = LongArray(boards.size)
        private val remaining = LongArray(boards.size)
        private var epoch = 1L
        private var boardsLeft = 0
        private var savedBoardsLeft = 0
        private var savedEpoch = 0L
        private var picking = false
        private val touched = IntArrayList()
        private val savedPosition = LongArrayList()
        private val savedRemaining = LongArrayList()
        private val savedFinished = LongArrayList()

        val hasRemaining: Boolean get() = boardsLeft > 0

        fun hasRemaining(board: Int): Boolean = finished[board] != epoch

        fun boardIndex(factorId: Int): Int = boards.binarySearch(factorId)

        fun invalidate() {
            picking = false
            epoch++
            boardsLeft = 0
        }

        fun start() {
            boardsLeft = boards.size
        }

        fun beginPick() {
            picking = true
            savedBoardsLeft = boardsLeft
            savedEpoch = epoch
            touched.clear()
            savedPosition.clear()
            savedRemaining.clear()
            savedFinished.clear()
        }

        fun finishPick(cancelled: Boolean) {
            picking = false
            if (!cancelled || savedEpoch != epoch) return
            for (i in touched.size - 1 downTo 0) {
                val id = touched[i]
                position[id] = savedPosition[i]
                remaining[id] = savedRemaining[i]
                finished[id] = savedFinished[i]
            }
            boardsLeft = savedBoardsLeft
        }

        fun visit(state: LocalSearchState, id: Int, sink: MoveSink) {
            val fid = boards[id]
            val invariant = state.factors[fid] as AllDifferentInvariant
            val size = invariant.swapCoordinateCount
            val pairs = size.toLong() * (size - 1) / 2
            if (initialized[id] != epoch) {
                initialized[id] = epoch
                remaining[id] = pairs
                position[id] = if (pairs > 0L) state.rng.nextLong(pairs) else 0L
                stride[id] = if (pairs > 1L) coprimeStride(state, pairs) else 1L
            }
            if (picking) {
                touched.add(id)
                savedPosition.add(position[id])
                savedRemaining.add(remaining[id])
                savedFinished.add(finished[id])
            }
            if (remaining[id] > 0L && !state.violated.contains(fid)) {
                val left = leftCoordinate(size, position[id])
                val right = left + 1 + (position[id] - pairsBefore(size, left)).toInt()
                sink.proposer = fid
                if (invariant.allowsSwapCoordinate(state, left, sink)) {
                    invariant.proposeSwap(state, left, right, sink)
                }
                remaining[id]--
                position[id] = (position[id] + stride[id]) % pairs
            } else {
                remaining[id] = 0L
            }
            if (remaining[id] == 0L) {
                finished[id] = epoch
                boardsLeft--
            }
        }

        private fun leftCoordinate(size: Int, pair: Long): Int {
            var low = 0
            var high = size - 1
            while (low + 1 < high) {
                val middle = low + (high - low) / 2
                if (pairsBefore(size, middle) <= pair) low = middle else high = middle
            }
            return low
        }

        private fun coprimeStride(state: LocalSearchState, pairs: Long): Long {
            var step = state.rng.nextLong(1L, pairs)
            while (gcd(step, pairs) != 1L) step++
            return step
        }

        private fun gcd(first: Long, second: Long): Long {
            var a = first
            var b = second
            while (b != 0L) {
                val remainder = a % b
                a = b
                b = remainder
            }
            return a
        }

        private fun pairsBefore(size: Int, left: Int): Long = left.toLong() * (2L * size - left - 1L) / 2
    }
}
