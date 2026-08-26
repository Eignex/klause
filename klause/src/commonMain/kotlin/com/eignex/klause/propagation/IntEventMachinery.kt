package com.eignex.klause.propagation

import com.eignex.klause.solver.Problem
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntObjectMap

/**
 * Typed int-event wakeup machinery for [PropagationState]: the per-`(intVar, kind)` advisor index,
 * the per-var pending-kind masks, and the per-factor dirty-variable delta accumulators.
 * All four structures are empty when no factor opts in, so the common case allocates nothing;
 * [incremental] mode forces them live so a mid-life factor subscribing to typed events (or
 * consuming the delta) wakes correctly even when the initial problem had none.
 */
internal class IntEventMachinery(projection: PropagationProblem, incremental: Boolean) {
    private val problem: Problem = projection.problem

    /** Whether the typed int-event machinery ([dirtyKinds] / [baseFlat]) is live. */
    val on: Boolean = projection.usesIntEventWatchers || incremental

    /** Whether the per-factor dirty-variable delta accumulators are live. */
    val deltaOn: Boolean = projection.usesIntEventDeltaConsumers || incremental

    /**
     * Per-`(intVar, kind)` advisor index, the int-side analog of [BoolWatcherIndex.byLit]: slot
     * `[IntEvent.pack(v, kind)]` lists the factor ids that subscribed to that event via
     * [Propagator.initialIntEventWatches], read by `enqueueForIntChange` to wake only the factors that
     * care about the kind of change that just happened. The base factors' subscriptions are held as a
     * CSR ([baseOffsets] / [baseFlat]) built once at construction rather than one [IntArrayList] per
     * `numIntVars * IntEvent.COUNT` slot: on a large model most slots have no subscriber, and the wide
     * array of tiny lists dominated [PropagationState] construction. The CSR is one prefix-sum pass plus
     * one fill pass over the base propagators, both O(subscriptions); reads are a contiguous slice and the
     * per-slot subscriber order is ascending base factor id — exactly the order the former per-slot list
     * appends produced. Mid-life factors ([PropagationState.addMidlifeFactor]) subscribe into [overflow].
     */
    internal val baseOffsets: IntArray
    internal val baseFlat: IntArray

    /** Mid-life subscriptions (packed slot → factor ids in add order), allocated lazily — the common case
     *  adds no int-event-watching factor mid-run (presolve appends Linear rows, which watch nothing), so
     *  this stays `null` and the read pays only a null check past the CSR. */
    @Suppress("DoubleMutabilityForCollection") // null until the first mid-life subscription; assigned once, lazily
    internal var overflow: MutableIntObjectMap<IntArrayList>? = null

    init {
        if (on) {
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
            baseOffsets = EmptyIntArray
            baseFlat = EmptyIntArray
        }
    }

    /** Subscribe a mid-life factor [fid] to the event [packed] ([IntEvent.pack]); the base CSR is frozen,
     *  so post-construction subscriptions go to the sparse [overflow]. */
    fun subscribeMidlife(packed: Int, fid: Int) {
        val o = overflow ?: MutableIntObjectMap<IntArrayList>().also { overflow = it }
        o.getOrPut(packed) { IntArrayList(initialCapacity = 1) }.add(fid)
    }

    /** Visit every factor subscribed to event [packed]: base subscribers (CSR, ascending factor id) then
     *  mid-life subscribers (overflow, add order) — the order the former single per-slot list held. */
    inline fun forEachWatcher(packed: Int, action: (Int) -> Unit) {
        for (k in baseOffsets[packed] until baseOffsets[packed + 1]) action(baseFlat[k])
        overflow?.get(packed)?.let { list -> for (i in 0 until list.size) action(list[i]) }
    }

    /**
     * Per-int-var bitmask of the [IntEvent] kinds that occurred since the variable was last
     * drained — recorded by `markIntDirty` alongside the dirty-int queue and consumed (then
     * cleared) by `enqueueForIntChange` to wake exactly the subscribed advisors. Empty (aliasing
     * the shared [EmptyIntArray]) when no factor subscribes, so the common case allocates nothing
     * and skips the bookkeeping entirely.
     *
     * Sound to over-set (extra bit ⇒ harmless extra wake) but never under-set (a missing bit drops
     * a wake a subscriber relied on). Mark and drain are paired through the dirty-int queue; since
     * the driver only marks/undoes between propagation cycles — when the queue is empty and thus
     * every mask already cleared — no stale bits survive a backtrack.
     */
    val dirtyKinds: IntArray = if (on) IntArray(problem.numIntVars) else EmptyIntArray

    /**
     * Per-factor dirty-variable delta accumulator: for each factor with
     * [Propagator.consumesIntEventDelta], the subscribed variables that fired since the consumer
     * last drained. `enqueueForIntChange` appends a variable when it wakes the consumer via the
     * advisor index ([dirtyMark] deduplicates), and `drainIntEventDirtyVars` returns and clears
     * the set on a fire.
     *
     * **Drift-tolerant superset.** It is never cleared on backtrack, so after a pop it may list a
     * variable whose change was undone — harmless, because the consumer diffs its own reversible
     * baseline and finds no change for a stale variable. It is therefore always a *superset* of
     * "changed since this consumer last fired": no real change is ever missed (every change to a
     * subscribed variable wakes the consumer and appends), which is the soundness-critical
     * direction. `null` per non-consuming factor; both lists are empty when the problem has no
     * consumer, and grow-only (parallel to the factor store) — `addMidlifeFactor` appends a slot
     * per new factor so a mid-life delta consumer's accumulator stays in bounds.
     */
    val dirtyVars: ArrayList<IntArrayList?> =
        if (deltaOn) {
            ArrayList<IntArrayList?>(problem.numFactors).apply { repeat(problem.numFactors) { add(null) } }
        } else {
            ArrayList()
        }

    /** Dedup companion to [dirtyVars]; see its doc. */
    val dirtyMark: ArrayList<IntHashSet?> =
        if (deltaOn) {
            ArrayList<IntHashSet?>(problem.numFactors).apply { repeat(problem.numFactors) { add(null) } }
        } else {
            ArrayList()
        }
}
