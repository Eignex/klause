package com.eignex.klause.propagation

/**
 * Typed integer-domain change events — the int-side analog of the Boolean two-watched-literal
 * scheme ([BoolWatcherIndex.byLit]). A factor subscribes to specific `(intVar, kind)`
 * pairs via [com.eignex.klause.propagation.Propagator.initialIntEventWatches] and is then woken *only* when
 * that kind of change happens to that variable, instead of on every change to any of its
 * [com.eignex.klause.solver.Factor.intVars] (the default occurrence-list wakeup). This is the
 * scheduling substrate for advisor-style incremental propagation (Gecode advisors / OR-Tools
 * watched bounds / Choco); see epic #619.
 *
 * The four kinds partition the ways a domain can shrink:
 *  - [LB_RAISED]      — the lower bound increased (`tightenIntMin`, or an edge `excludeIntValue`
 *                       that advanced `min`).
 *  - [UB_LOWERED]     — the upper bound decreased (`tightenIntMax`, or an edge exclusion that
 *                       pulled `max` in).
 *  - [VALUE_REMOVED]  — an *interior* value was carved out, leaving both bounds intact.
 *  - [FIXED]          — the domain became a singleton. Emitted *in addition to* whichever bound
 *                       kind caused the fixing, so a factor that only cares about assignment can
 *                       subscribe to [FIXED] alone.
 *
 * A `(intVar, kind)` subscription is encoded as a single `Int` via [pack]; the same value is the
 * slot index into [IntEventMachinery.watchersBySlot]. Encoding is `intVar * COUNT + kind`,
 * so [intVarOf] / [kindOf] recover the components.
 *
 * **Soundness contract:** the per-variable kind mask the mutators record (see
 * `PropagationState.markIntDirty`) must be a *superset* of the kinds that actually occurred — a
 * missing bit drops a wake the factor was relying on (unsound: a subscribed factor never fires for
 * a change it cares about), whereas a spurious extra bit only costs a harmless extra wake. The
 * mutators set the bit unconditionally at every dirtying site, so the only failure mode is
 * over-waking, which is sound.
 */
internal object IntEvent {
    const val LB_RAISED: Int = 0
    const val UB_LOWERED: Int = 1
    const val VALUE_REMOVED: Int = 2
    const val FIXED: Int = 3

    /** Number of distinct event kinds; also the per-variable stride in the slot index. */
    const val COUNT: Int = 4

    const val LB_RAISED_BIT: Int = 1 shl LB_RAISED
    const val UB_LOWERED_BIT: Int = 1 shl UB_LOWERED
    const val VALUE_REMOVED_BIT: Int = 1 shl VALUE_REMOVED
    const val FIXED_BIT: Int = 1 shl FIXED

    /** Encode a `(intVar, kind)` subscription — also the slot index into the watcher table. */
    fun pack(intVar: Int, kind: Int): Int = intVar * COUNT + kind

    /** The int variable of a [pack]ed subscription / slot. */
    fun intVarOf(packed: Int): Int = packed / COUNT

    /** The event kind of a [pack]ed subscription / slot. */
    fun kindOf(packed: Int): Int = packed % COUNT

    /**
     * The standard advisor subscription for a bounds-consistent / interval propagator: [LB_RAISED]
     * and [UB_LOWERED] on every distinct variable in [vars], and nothing else. A factor whose
     * `propagate` reads only each variable's `min`/`max` (Linear, Product, ArrayMinMax, bounds
     * `AllDifferent`, …) returns this from
     * [com.eignex.klause.propagation.Propagator.initialIntEventWatches] so it wakes on bound moves but not on
     * interior [VALUE_REMOVED] carves it could not act on. Duplicate ids in [vars] are subscribed
     * once (bound subscriptions are idempotent), so aliased operands are handled cleanly.
     */
    fun boundEventWatches(vars: IntArray): IntArray {
        val distinct = vars.toHashSet()
        val out = IntArray(distinct.size * 2)
        var w = 0
        for (v in distinct) {
            out[w++] = pack(v, LB_RAISED)
            out[w++] = pack(v, UB_LOWERED)
        }
        return out
    }
}
