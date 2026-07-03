package com.eignex.klause.factor.table.internals

import com.eignex.klause.propagation.IntEvent

/** Subscribes to all four event types for each distinct variable in [vars]. */
internal fun allEventWatches(vars: IntArray): IntArray {
    val distinct = vars.toHashSet()
    val out = IntArray(distinct.size * IntEvent.COUNT)
    var w = 0
    for (v in distinct) {
        out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
        out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        out[w++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
        out[w++] = IntEvent.pack(v, IntEvent.FIXED)
    }
    return out
}
