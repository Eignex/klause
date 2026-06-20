package com.eignex.klause.solver.factor.scheduling.internals

/** LS-side payload for Cumulative. Owns the usage timeline, the running overage, and the
 *  cached capacity so capacity-var changes can recompute overage in one O(horizon) scan. */
class CumulativeLsState(
    /** Lowest time-slot index in the [usage] array. */
    val tLow: Int,
    /** Resource usage per time slot, indexed relative to [tLow]. */
    val usage: IntArray,
    /** Sum of `max(0, usage[t] - cap)` across all slots. */
    var overage: Int,
    /** Current capacity ceiling (mirrors the capacity variable's assigned value). */
    var cap: Int,
)
