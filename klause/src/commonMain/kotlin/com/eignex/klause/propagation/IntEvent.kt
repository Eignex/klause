package com.eignex.klause.propagation

// Typed integer-domain change events, the scheduling substrate for advisor-style incremental propagation
// (M. Z. Lagerkvist, C. Schulte, "Advisors for Incremental Propagation", CP 2007,
// https://doi.org/10.1007/978-3-540-74970-7_30). A factor subscribed to `(intVar, kind)` wakes only on that
// change. [FIXED] is emitted in addition to the bound kind that caused it, so an assignment-only factor can
// subscribe to it alone.
//
// Soundness: the kind mask the mutators record must be a superset of the kinds that occurred. A missing bit
// drops a wake a factor relies on; an extra bit only over-wakes.
internal object IntEvent {
    const val LB_RAISED: Int = 0
    const val UB_LOWERED: Int = 1
    const val VALUE_REMOVED: Int = 2
    const val FIXED: Int = 3

    const val COUNT: Int = 4

    const val LB_RAISED_BIT: Int = 1 shl LB_RAISED
    const val UB_LOWERED_BIT: Int = 1 shl UB_LOWERED
    const val VALUE_REMOVED_BIT: Int = 1 shl VALUE_REMOVED
    const val FIXED_BIT: Int = 1 shl FIXED

    // Also the slot index into the watcher table.
    fun pack(intVar: Int, kind: Int): Int = intVar * COUNT + kind

    fun intVarOf(packed: Int): Int = packed / COUNT

    fun kindOf(packed: Int): Int = packed % COUNT

    // For a propagator that reads only each variable's bounds: it wakes on bound moves but not on interior carves
    // it could not act on. Duplicate ids subscribe once.
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
