package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.IntArrayList

/*
 * Backtrackable mutable state for incremental propagators, riding the same O(changes-since-mark)
 * undo trail the engine uses for bool/int domains (see PropagationState.revTrail). Each write,
 * while PropagationState.undoLogging is on, records the cell's prior value and registers the cell
 * on the reversible trail; PropagationState.undoTo replays the trail top-down so every cell lands
 * back on its mark-time value. This replaces the O(state) SnapshottablePayload.snapshotCopy for
 * factor state that changes incrementally (matching, flow, sparse sets, ...).
 *
 * Soundness rests on one rule: every mutation goes through set / the reversible API — a raw write
 * that skips the log will not be undone and silently corrupts state across a backtrack. The trail
 * itself is restore-correct by construction (it replays recorded prior values), so the bug class is
 * confined to "did the propagator route all its mutations through a reversible".
 */

/** A cell that can roll back one logged mutation; registered on [PropagationState.revTrail]. */
internal interface Trailed {
    /** Undo the single most-recent logged mutation (LIFO). Called by [PropagationState.undoTo]. */
    fun restore()
}

/** A backtrackable `Int`. Reads via [value]; mutate via [set]. */
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

/** A backtrackable reference cell. */
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

/** A backtrackable `IntArray`: per-element writes are individually trailed and rolled back. */
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

/**
 * Per-variable forward-delta cursor for an incremental propagator: reports the values removed from a
 * watched int var since the propagator's previous fire, and rolls back with the search so a re-fire
 * after a backtrack sees the correct delta. The cursor snapshot is a [RevRef] on the engine trail;
 * the removal set is the immutable-domain diff "present at the snapshot, absent now".
 *
 * Only *forward* removals are reported. A propagator built on reversible state has its own structure
 * restored on backtrack, so it never needs an "added-back" delta — it only processes what newly left
 * while descending. Wake it however you like (the default wake-on-any-change is fine); the cursor
 * accumulates every removal since its last fire, so multiple intervening changes coalesce correctly.
 */
internal class DomainDelta(state: PropagationState, initial: IntDomain) {
    @PublishedApi internal val snapshot = RevRef(state, initial)

    /** Invoke [removed] for each value present at the cursor's last advance but absent from
     *  [current], then advance the cursor to [current]. No work / no trail write when nothing
     *  changed (the domain object is identity-unchanged since the last advance). */
    inline fun forEachRemoved(current: IntDomain, removed: (Int) -> Unit) {
        val before = snapshot.value
        if (before === current) return
        before.forEach { v -> if (v !in current) removed(v) }
        snapshot.set(current)
    }
}
