package com.eignex.klause.factor.scheduling.internals

/** Effective (fixed) durations / resources / capacity snapshot for Cumulative propagation.
 *  `null` at the call site when any var-arg is still open — propagation defers in that case. */
class CumulativeEff(
    /** Fixed durations, one per task. */
    val dur: LongArray,
    /** Fixed resource requirements, one per task. */
    val res: LongArray,
    /** Capacity ceiling. */
    val cap: Long,
)
