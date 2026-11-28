package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

// Every structure is empty when no factor opts in, so the common case allocates nothing. Incremental mode forces
// them live so a mid-life factor that subscribes or consumes the delta wakes correctly.
internal class IntEventMachinery private constructor(
    projection: PropagationProblem,
    incremental: Boolean,
    preparedOffsets: IntArray?,
    preparedFlat: IntArray?,
    preparedDirtyVars: ArrayList<IntArrayList?>?,
    preparedDirtyMark: ArrayList<IntHashSet?>?,
) {
    constructor(
        projection: PropagationProblem,
        incremental: Boolean,
    ) : this(projection, incremental, null, null, null, null)
    private val problem: Problem = projection.problem

    val on: Boolean = projection.usesIntEventWatchers || incremental

    val deltaOn: Boolean = projection.usesIntEventDeltaConsumers || incremental

    // Base subscriptions as a CSR built once: one list per `numIntVars * IntEvent.COUNT` slot would dominate
    // construction on a large model where most slots have no subscriber.
    internal val baseOffsets: IntArray
    internal val baseFlat: IntArray

    // Lazy: mid-life factors rarely subscribe (presolve appends Linear rows, which watch nothing).
    @Suppress("DoubleMutabilityForCollection") // null until the first mid-life subscription; assigned once, lazily
    internal var overflow: IntEventOverflow? = null

    init {
        if (preparedOffsets != null) {
            baseOffsets = preparedOffsets
            baseFlat = checkNotNull(preparedFlat)
        } else if (on) {
            val numSlots = problem.numIntVars * IntEvent.COUNT
            val offsets = IntArray(numSlots + 1)
            for (fid in 0 until problem.numFactors) {
                projection.propagators[fid].initialIntEventWatches?.let { for (packed in it) offsets[packed + 1]++ }
            }
            for (s in 1..numSlots) offsets[s] += offsets[s - 1]
            val flat = IntArray(offsets[numSlots])
            val cursor = offsets.copyOf()
            for (fid in 0 until problem.numFactors) {
                projection.propagators[fid].initialIntEventWatches?.let {
                    for (packed in it) {
                        flat[cursor[packed]++] =
                            fid
                    }
                }
            }
            baseOffsets = offsets
            baseFlat = flat
        } else {
            baseOffsets = emptyIntArray()
            baseFlat = emptyIntArray()
        }
    }

    // The base CSR is frozen, so later subscriptions go to the sparse [overflow].
    fun subscribeMidlife(packed: Int, fid: Int): Long {
        var work = 0L
        val index = overflow ?: IntEventOverflow().also {
            overflow = it
            work += 12L
        }
        return work + index.subscribe(packed, fid)
    }

    fun remapLearned(remap: IntArray, base: Int): Long {
        val previous = overflow ?: return 0L
        val (replacement, work) = previous.remap(remap, base)
        overflow = replacement
        return work
    }

    inline fun forEachWatcher(packed: Int, charge: (Long) -> Unit, action: (Int) -> Unit) {
        for (k in baseOffsets[packed] until baseOffsets[packed + 1]) action(baseFlat[k])
        val index = overflow
        val list = index?.get(packed)
        if (index != null) charge(index.lookupWork)
        if (list != null) for (i in 0 until list.size) action(list[i])
    }

    // Kinds that occurred since the variable was last drained. Over-setting only over-wakes; under-setting drops
    // a wake. The driver marks and undoes only between propagation cycles, when every mask is clear, so no
    // stale bits survive a backtrack.
    val dirtyKinds: IntArray = if (on) IntArray(problem.numIntVars) else emptyIntArray()

    // Per delta-consuming factor, the subscribed variables that fired since it last drained. Never cleared on
    // backtrack, so it is a superset of the real changes: a stale entry is harmless because the consumer diffs
    // its own reversible baseline, and no real change is missed. Grows with the factor store so a mid-life
    // consumer stays in bounds.
    val dirtyVars: ArrayList<IntArrayList?> =
        preparedDirtyVars ?: if (deltaOn) {
            ArrayList<IntArrayList?>(problem.numFactors).apply { repeat(problem.numFactors) { add(null) } }
        } else {
            ArrayList()
        }

    val dirtyMark: ArrayList<IntHashSet?> =
        preparedDirtyMark ?: if (deltaOn) {
            ArrayList<IntHashSet?>(problem.numFactors).apply { repeat(problem.numFactors) { add(null) } }
        } else {
            ArrayList()
        }
    companion object {
        fun preparation(projection: PropagationProblem): Sequence<IntEventMachinery?> = sequence {
            val problem = projection.problem
            val on = projection.usesIntEventWatchers
            val deltaOn = projection.usesIntEventDeltaConsumers
            val slots = if (on) problem.numIntVars * IntEvent.COUNT else 0
            val offsets = if (on) IntArray(slots + 1) else emptyIntArray()
            yield(null)
            var visits = 0
            if (on) {
                for (prop in projection.propagators) {
                    prop.initialIntEventWatches?.let { watches ->
                        for (packed in watches) {
                            offsets[packed + 1]++
                            if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
                        }
                    }
                    if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
                }
                for (slot in 1..slots) {
                    offsets[slot] += offsets[slot - 1]
                    if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
                }
            }
            val flat = if (on) IntArray(offsets[slots]) else emptyIntArray()
            yield(null)
            val cursor = if (on) IntArray(offsets.size) else emptyIntArray()
            yield(null)
            for (slot in cursor.indices) {
                cursor[slot] = offsets[slot]
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            if (on) for (fid in projection.propagators.indices) {
                projection.propagators[fid].initialIntEventWatches?.let { watches ->
                    for (packed in watches) {
                        flat[cursor[packed]++] = fid
                        if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
                    }
                }
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val dirtyVars = ArrayList<IntArrayList?>(if (deltaOn) problem.numFactors else 0)
            yield(null)
            val dirtyMark = ArrayList<IntHashSet?>(if (deltaOn) problem.numFactors else 0)
            yield(null)
            if (deltaOn) repeat(problem.numFactors) {
                dirtyVars.add(null)
                dirtyMark.add(null)
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            yield(IntEventMachinery(projection, false, offsets, flat, dirtyVars, dirtyMark))
        }
    }
}
