package com.eignex.klause.propagation

import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

// Backtrackable state for incremental propagators: while undo logging is on, writes record each cell's prior value
// and undoTo restores them in reverse, O(changes since the mark). Every mutation must go through this API; a raw
// write bypasses restoration and corrupts propagator state across backtracking.

internal interface Trailed {
    fun restore()
}

internal class RevInt(private val state: PropagationState, initial: Int) : Trailed {
    var value: Int = initial
        private set

    private val prior = IntArrayList()

    fun set(v: Int) {
        if (v == value) return
        if (state.undoLogging) {
            prior.add(value)
            state.logReversible(this)
        }
        value = v
    }

    override fun restore() {
        value = prior.last()
        prior.truncateTo(prior.size - 1)
    }
}

internal class RevLong(private val state: PropagationState, initial: Long) : Trailed {
    var value: Long = initial
        private set

    private val prior = LongArrayList()

    fun set(v: Long) {
        if (v == value) return
        if (state.undoLogging) {
            prior.add(value)
            state.logReversible(this)
        }
        value = v
    }

    override fun restore() {
        value = prior.last()
        prior.truncateTo(prior.size - 1)
    }
}

internal class RevRef<T>(private val state: PropagationState, initial: T) : Trailed {
    var value: T = initial
        private set

    private val prior = ArrayList<T>()

    fun set(v: T) {
        if (v == value) return
        if (state.undoLogging) {
            prior.add(value)
            state.logReversible(this)
        }
        value = v
    }

    override fun restore() {
        value = prior.removeAt(prior.size - 1)
    }
}

internal class RevLongArray(private val state: PropagationState, size: Int, init: Long = 0L) : Trailed {
    private val data = LongArray(size) { init }
    private val priorIdx = IntArrayList()
    private val priorVal = LongArrayList()

    val size: Int get() = data.size

    operator fun get(i: Int): Long = data[i]

    operator fun set(i: Int, v: Long) {
        if (v == data[i]) return
        if (state.undoLogging) {
            priorIdx.add(i)
            priorVal.add(data[i])
            state.logReversible(this)
        }
        data[i] = v
    }

    override fun restore() {
        val i = priorIdx.last()
        data[i] = priorVal.last()
        priorVal.truncateTo(priorVal.size - 1)
        priorIdx.truncateTo(priorIdx.size - 1)
    }
}

internal class RevIntArray(private val state: PropagationState, size: Int, init: Int = 0) : Trailed {
    private val data = IntArray(size) { init }
    private val priorIdx = IntArrayList()
    private val priorVal = IntArrayList()

    val size: Int get() = data.size

    operator fun get(i: Int): Int = data[i]

    operator fun set(i: Int, v: Int) {
        if (v == data[i]) return
        if (state.undoLogging) {
            priorIdx.add(i)
            priorVal.add(data[i])
            state.logReversible(this)
        }
        data[i] = v
    }

    override fun restore() {
        val i = priorIdx.last()
        data[i] = priorVal.last()
        priorIdx.truncateTo(priorIdx.size - 1)
        priorVal.truncateTo(priorVal.size - 1)
    }
}
