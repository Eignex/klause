package com.eignex.klause.solver.result

/** Completed relation counts and hypothetical propagation used only for value ordering. */
data class ValueOrderingStats(
    /** Orderings answered by completed extensional counters. */
    val supportOrders: Long = 0L,
    /** Orderings that entered residual-domain probing. */
    val probeOrders: Long = 0L,
    /** Candidate probes that completed a fixpoint or refutation. */
    val probes: Long = 0L,
    /** Completed probes that refuted their candidate. */
    val refutations: Long = 0L,
    /** Work in probing orderings, including interrupted probes, restoration and bounded score visits. */
    val probeWork: Long = 0L,
    /** Orderings that exhausted their local cooperative work allowance. */
    val cappedOrders: Long = 0L,
) {
    /** Add observations from independent worker lifetimes. */
    fun mergedWith(other: ValueOrderingStats): ValueOrderingStats = ValueOrderingStats(
        supportOrders + other.supportOrders,
        probeOrders + other.probeOrders,
        probes + other.probes,
        refutations + other.refutations,
        probeWork + other.probeWork,
        cappedOrders + other.cappedOrders,
    )
}

internal class ValueOrderingStatsSink {
    var supportOrders = 0L
    var probeOrders = 0L
    var probes = 0L
    var refutations = 0L
    var probeWork = 0L
    var cappedOrders = 0L

    fun snapshot() = ValueOrderingStats(supportOrders, probeOrders, probes, refutations, probeWork, cappedOrders)
}
