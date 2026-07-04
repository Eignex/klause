package com.eignex.klause.propagation

import com.eignex.klause.solver.Problem
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * Typed int-event wakeup machinery for [PropagationState]: the per-`(intVar, kind)` advisor index,
 * the per-var pending-kind masks, and the per-factor dirty-variable delta accumulators (#624).
 * All four structures are empty when no factor opts in, so the common case allocates nothing;
 * [incremental] mode forces them live so a mid-life factor subscribing to typed events (or
 * consuming the delta) wakes correctly even when the initial problem had none.
 */
internal class IntEventMachinery(problem: Problem, incremental: Boolean) {
    /** Whether the typed int-event machinery ([dirtyKinds] / [watchersBySlot]) is live. */
    val on: Boolean = problem.usesIntEventWatchers || incremental

    /** Whether the per-factor dirty-variable delta accumulators are live. */
    val deltaOn: Boolean = problem.usesIntEventDeltaConsumers || incremental

    /**
     * Per-`(intVar, kind)` advisor index, the int-side analog of [BoolWatcherIndex.byLit]: slot
     * `[IntEvent.pack(v, kind)]` lists the factor ids that subscribed to that event via
     * [Propagator.initialIntEventWatches], so `enqueueForIntChange` can wake only the factors that
     * care about the kind of change that just happened. Sized `numIntVars * IntEvent.COUNT` and
     * populated once at construction; the subscriptions are static — a propagator that loses
     * interest in a variable simply ignores the wake, which is sound.
     */
    val watchersBySlot: Array<IntArrayList> =
        if (on) {
            Array(problem.numIntVars * IntEvent.COUNT) { IntArrayList(initialCapacity = 1) }
        } else {
            emptyArray()
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
     * Per-factor dirty-variable delta accumulator (#624): for each factor with
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
