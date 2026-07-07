package com.eignex.klause.factor.scheduling.internals

/** LS-side payload for Cumulative. Owns the usage timeline, the running overage, and the
 *  cached capacity so capacity-var changes can recompute overage in one O(horizon) scan. */
class CumulativeLsState(
    /** Lowest time value in the [usage] array. */
    val tLow: Long,
    /** Resource usage per time slot, indexed relative to [tLow]. */
    val usage: LongArray,
    /** Sum of `max(0, usage[t] - cap)` across all slots. */
    var overage: Long,
    /** Current capacity ceiling (mirrors the capacity variable's assigned value). */
    var cap: Long,
)
