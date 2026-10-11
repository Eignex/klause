package com.eignex.klause.solver.result

/**
 * Run-level envelope: which backend ran, how long it took, and whether it hit its budget. Not a
 * counter cluster — a backend's [SolveStatsSink] measures its run window. A coordinator supplies
 * its own invocation envelope after merging worker snapshots.
 * See [SolveStats].
 */
data class RunStats(
    /** Short backend identifier (e.g. "backtrack", "ls"); "mixed" after merging heterogeneous workers. */
    val backend: String = "",
    /** Wall time (ms) for the run; merging workers takes their maximum until a coordinator supplies its envelope. */
    val wallMs: Long = 0L,
    /** Budget exhausted before a definitive verdict; ORed across merged workers. */
    val timedOut: Boolean = false,
) {
    /** Combine two workers: backend degrades to "mixed" on mismatch, wall time maxes, timed-out ORs. */
    fun mergedWith(o: RunStats): RunStats = RunStats(
        backend = if (backend == o.backend) backend else "mixed",
        wallMs = maxOf(wallMs, o.wallMs),
        timedOut = timedOut || o.timedOut,
    )
}
